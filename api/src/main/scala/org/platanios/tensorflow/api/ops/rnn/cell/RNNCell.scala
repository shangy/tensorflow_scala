/* Copyright 2017, Emmanouil Antonios Platanios. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.platanios.tensorflow.api.ops.rnn.cell

import org.platanios.tensorflow.api.core.Shape
import org.platanios.tensorflow.api.core.exception.InvalidArgumentException
import org.platanios.tensorflow.api.implicits.Implicits._
import org.platanios.tensorflow.api.ops
import org.platanios.tensorflow.api.ops.{Basic, Math, NN, Op, Output}
import org.platanios.tensorflow.api.types.{DataType, INT32}

import shapeless._
import shapeless.ops.hlist.Tupler

/** Contains functions for constructing ops related to recurrent neural network (RNN) cells.
  *
  * @author Emmanouil Antonios Platanios
  */
trait RNNCell[O, OS, S, SS] {
  def outputShape: OS
  def stateShape: SS
  def forward(input: RNNCell.Tuple[O, S]): RNNCell.Tuple[O, S]
  def apply(input: RNNCell.Tuple[O, S]): RNNCell.Tuple[O, S] = forward(input)
}

object RNNCell {
  type BasicCell = RNNCell[Output, Shape, Output, Shape]

  class Tuple[O, S](val output: O, val state: S)

  type BasicTuple = Tuple[Output, Output]

  object Tuple {
    def apply[O, S](output: O, state: S): Tuple[O, S] = new Tuple(output, state)
  }

  type LSTMCell = RNNCell[Output, Shape, (Output, Output), (Shape, Shape)]
  type LSTMTuple = Tuple[Output, (Output, Output)]

  def LSTMTuple(output: Output, state: (Output, Output)): LSTMTuple = Tuple(output, state)

  trait Supported[T] {
    type ShapeType

    def zero(batchSize: Output, dataType: DataType, shape: ShapeType, name: String = "Zero"): T
    def outputs(value: T): Seq[Output]
    def shapes(shape: ShapeType): Seq[Shape]

    def fromOutputs(value: T, outputs: Seq[Output]): T = segmentOutputs(value, outputs)._1
    def segmentOutputs(value: T, outputs: Seq[Output]): (T, Seq[Output])

    def fromShapes(value: T, shapes: Seq[Shape]): ShapeType = segmentShapes(value, shapes)._1
    def segmentShapes(value: T, shapes: Seq[Shape]): (ShapeType, Seq[Shape])
  }

  object Supported {
    type Aux[T, S] = Supported[T] {type ShapeType = S}

    def apply[T, S](implicit ev: Supported.Aux[T, S]): Supported.Aux[T, S] = ev

    implicit val outputSupported: Supported.Aux[Output, Shape] = new Supported[Output] {
      override type ShapeType = Shape

      override def zero(batchSize: Output, dataType: DataType, shape: Shape, name: String = "Zero"): Output = {
        val staticBatchSize = Output.constantValue(batchSize).map(_.scalar.asInstanceOf[Int]).getOrElse(-1)
        Op.createWithNameScope(name, Set(batchSize.op)) {
          val fullShape = Basic.concatenate(Seq(batchSize.expandDims(0), shape.toOutput(batchSize.dataType)), axis = 0)
          val zero = Basic.fill(dataType, fullShape)(0)
          zero.setShape(Shape(staticBatchSize) ++ shape)
          zero
        }
      }

      override def outputs(value: Output): Seq[Output] = Seq(value)
      override def shapes(shape: Shape): Seq[Shape] = Seq(shape)

      override def segmentOutputs(value: Output, outputs: Seq[Output]): (Output, Seq[Output]) = {
        (outputs.head, outputs.tail)
      }

      override def segmentShapes(value: Output, shapes: Seq[Shape]): (Shape, Seq[Shape]) = {
        (shapes.head, shapes.tail)
      }
    }

    implicit val hnilSupported: Supported.Aux[HNil, HNil] = new Supported[HNil] {
      override type ShapeType = HNil
      override def zero(batchSize: Output, dataType: DataType, shape: HNil, name: String = "Zero"): HNil = HNil
      override def outputs(value: HNil): Seq[Output] = Seq.empty[Output]
      override def shapes(shape: HNil): Seq[Shape] = Seq.empty[Shape]
      override def segmentOutputs(value: HNil, outputs: Seq[Output]): (HNil, Seq[Output]) = (HNil, outputs)
      override def segmentShapes(value: HNil, shapes: Seq[Shape]): (HNil, Seq[Shape]) = (HNil, shapes)
    }

    implicit def recursiveSupportedConstructor[H, HS, T <: HList, TS <: HList](implicit
        supportedHead: Lazy[Aux[H, HS]],
        supportedTail: Aux[T, TS]
    ): Supported.Aux[H :: T, HS :: TS] = new Supported[H :: T] {
      override type ShapeType = HS :: TS

      override def zero(batchSize: Output, dataType: DataType, shape: HS :: TS, name: String = "Zero"): H :: T = {
        Op.createWithNameScope(name) {
          supportedHead.value.zero(batchSize, dataType, shape.head) ::
              supportedTail.zero(batchSize, dataType, shape.tail)
        }
      }

      override def outputs(value: H :: T): Seq[Output] = {
        supportedHead.value.outputs(value.head) ++ supportedTail.outputs(value.tail)
      }

      override def shapes(shape: HS :: TS): Seq[Shape] = {
        supportedHead.value.shapes(shape.head) ++ supportedTail.shapes(shape.tail)
      }

      override def segmentOutputs(value: H :: T, outputs: Seq[Output]): (H :: T, Seq[Output]) = {
        val (headOut, headRemaining) = supportedHead.value.segmentOutputs(value.head, outputs)
        val (tailOut, tailRemaining) = supportedTail.segmentOutputs(value.tail, headRemaining)
        (headOut :: tailOut, tailRemaining)
      }

      override def segmentShapes(value: H :: T, shapes: Seq[Shape]): (HS :: TS, Seq[Shape]) = {
        val (headOut, headRemaining) = supportedHead.value.segmentShapes(value.head, shapes)
        val (tailOut, tailRemaining) = supportedTail.segmentShapes(value.tail, headRemaining)
        (headOut :: tailOut, tailRemaining)
      }
    }

    // This also covers `OutputIndexedSlices` and `SparseOutput` as they are case classes (i.e., products).
    implicit def productSupportedConstructor[P <: Product, PS <: Product, L <: HList, LS <: HList](implicit
        genP: Generic.Aux[P, L],
        supportedL: Aux[L, LS],
        tupler: Tupler.Aux[L, P],
        tuplerS: Tupler.Aux[LS, PS],
        genPS: Generic.Aux[PS, LS]
    ): Supported.Aux[P, PS] = new Supported[P] {
      override type ShapeType = PS

      override def zero(batchSize: Output, dataType: DataType, shape: PS, name: String = "Zero"): P = {
        tupler(supportedL.zero(batchSize, dataType, genPS.to(shape), name))
      }

      override def outputs(value: P): Seq[Output] = supportedL.outputs(genP.to(value))
      override def shapes(shape: PS): Seq[Shape] = supportedL.shapes(genPS.to(shape))

      override def segmentOutputs(value: P, outputs: Seq[Output]): (P, Seq[Output]) = {
        val (out, remaining) = supportedL.segmentOutputs(genP.to(value), outputs)
        (tupler(out), remaining)
      }

      override def segmentShapes(value: P, shapes: Seq[Shape]): (PS, Seq[Shape]) = {
        val (out, remaining) = supportedL.segmentShapes(genP.to(value), shapes)
        (tuplerS(out), remaining)
      }
    }
  }

  /** $OpDocRNNCellBasicRNNCell
    *
    * @group RNNCellOps
    * @param  input      Input tuple consisting of the previous RNN cell output and state.
    * @param  kernel     Kernel matrix to use.
    * @param  bias       Bias vector to use.
    * @param  activation Activation function to use.
    * @param  name       Name scope for the created ops.
    * @return New RNN cell tuple, after this cell has been applied.
    * @throws InvalidArgumentException If the `input` shape is invalid.
    */
  @throws[InvalidArgumentException]
  private[cell] def basicRNNCell(
      input: BasicTuple, kernel: Output, bias: Output,
      activation: Output => Output = Math.tanh(_), name: String = "BasicRNNCell"
  ): BasicTuple = {
    Op.createWithNameScope(name) {
      val output = input.output
      val state = input.state
      if (output.rank != 2)
        throw InvalidArgumentException(s"Input must be rank-2 (provided rank-${output.rank}).")
      if (output.shape(1) == -1)
        throw InvalidArgumentException(s"Last axis of input shape (${output.shape}) must be known.")
      val linear = NN.addBias(Math.matmul(Basic.concatenate(Seq(output, state), axis = 1), kernel), bias)
      val newOutput = activation(linear)
      RNNCell.Tuple(newOutput, newOutput)
    }
  }

  /** $OpDocRNNCellGRUCell
    *
    * @group RNNCellOps
    * @param  input           Input tuple consisting of the previous RNN cell output and state.
    * @param  gateKernel      Gate kernel matrix to use.
    * @param  gateBias        Gate bias vector to use.
    * @param  candidateKernel Candidate kernel matrix to use.
    * @param  candidateBias   Candidate bias vector to use.
    * @param  activation      Activation function to use.
    * @param  name            Name scope for the created ops.
    * @return New RNN cell tuple, after this cell has been applied.
    * @throws InvalidArgumentException If the `input` shape is invalid.
    */
  @throws[InvalidArgumentException]
  private[cell] def gruCell(
      input: BasicTuple, gateKernel: Output, gateBias: Output, candidateKernel: Output,
      candidateBias: Output, activation: Output => Output = Math.tanh(_),
      name: String = "GRUCell"): BasicTuple = {
    Op.createWithNameScope(name) {
      val output = input.output
      val state = input.state
      if (output.rank != 2)
        throw InvalidArgumentException(s"Input must be rank-2 (provided rank-${output.rank}).")
      if (output.shape(1) == -1)
        throw InvalidArgumentException(s"Last axis of input shape (${output.shape}) must be known.")
      val gateIn = NN.addBias(Math.matmul(Basic.concatenate(Seq(output, state), axis = 1), gateKernel), gateBias)
      val value = Basic.splitEvenly(Math.sigmoid(gateIn), 2, axis = 1)
      val (r, u) = (value(0), value(1))
      val rState = Math.multiply(r, state)
      val c = NN.addBias(Math.matmul(Basic.concatenate(Seq(output, rState), axis = 1), candidateKernel), candidateBias)
      val newH = Math.add(Math.multiply(u, state), Math.multiply(1 - u, c))
      RNNCell.Tuple(newH, newH)
    }
  }

  /** $OpDocRNNCellBasicLSTMCell
    *
    * @group RNNCellOps
    * @param  input      Input tuple consisting of the previous RNN cell output and state.
    * @param  kernel     Kernel matrix to use.
    * @param  bias       Bias vector to use.
    * @param  activation Activation function to use.
    * @param  forgetBias Forget bias added to the forget gate.
    * @param  name       Name scope for the created ops.
    * @return New RNN cell tuple, after this cell has been applied.
    * @throws InvalidArgumentException If the `input` shape is invalid.
    */
  @throws[InvalidArgumentException]
  private[cell] def basicLSTMCell(
      input: LSTMTuple, kernel: Output, bias: Output,
      activation: Output => Output = Math.tanh(_),
      forgetBias: Float = 1.0f, name: String = "BasicLSTMCell"
  ): LSTMTuple = {
    Op.createWithNameScope(name) {
      val output = input.output
      if (output.rank != 2)
        throw InvalidArgumentException(s"Input must be rank-2 (provided rank-${output.rank}).")
      if (output.shape(1) == -1)
        throw InvalidArgumentException(s"Last axis of input shape (${output.shape}) must be known.")
      val one = ops.Basic.constant(1, INT32)
      // Parameters of gates are concatenated into one multiply for efficiency.
      val cPrev = input.state._1
      val mPrev = input.state._2
      val lstmMatrix = NN.addBias(Math.matmul(Basic.concatenate(Seq(output, mPrev), axis = 1), kernel), bias)
      // i = input gate, j = new input, f = forget gate, o = output gate
      val lstmMatrixBlocks = Basic.splitEvenly(lstmMatrix, 4, axis = one)
      val (i, j, f, o) = (lstmMatrixBlocks(0), lstmMatrixBlocks(1), lstmMatrixBlocks(2), lstmMatrixBlocks(3))
      val forgetBiasTensor = ops.Basic.constant(forgetBias, f.dataType)
      val c = Math.multiply(cPrev, Math.sigmoid(f + forgetBiasTensor)) + Math.multiply(Math.sigmoid(i), activation(j))
      val m = Math.multiply(activation(c), Math.sigmoid(o))
      LSTMTuple(m, (c, m))
    }
  }

  /** $OpDocRNNCellLSTMCell
    *
    * @group RNNCellOps
    * @param  input            Input tuple consisting of the previous RNN cell output and state.
    * @param  kernel           Kernel matrix to use.
    * @param  bias             Bias vector to use.
    * @param  cellClip         If different than `-1`, then the cell state is clipped by this value prior to the cell
    *                          output activation.
    * @param  wfDiag           If not `null`, then diagonal peep-hole connections are added from the forget gate to the
    *                          state, using these weights.
    * @param  wiDiag           If not `null`, then diagonal peep-hole connections are added from the input gate to the
    *                          state, using these weights.
    * @param  woDiag           If not `null`, then diagonal peep-hole connections are added from the output gate to the
    *                          state, using these weights.
    * @param  projectionKernel If not `null`, then this matrix is used to project the cell output.
    * @param  projectionClip   If different than `-1` and `projectionKernel` not `null`, then the projected output is
    *                          clipped by this value.
    * @param  activation       Activation function to use.
    * @param  forgetBias       Forget bias added to the forget gate.
    * @param  name             Name scope for the created ops.
    * @return New RNN cell tuple, after this cell has been applied.
    * @throws InvalidArgumentException If the `input` shape is invalid.
    */
  @throws[InvalidArgumentException]
  private[cell] def lstmCell(
      input: LSTMTuple, kernel: Output, bias: Output,
      cellClip: Float = -1,
      wfDiag: Output = null, wiDiag: Output = null, woDiag: Output = null,
      projectionKernel: Output = null, projectionClip: Float = -1,
      activation: Output => Output = Math.tanh(_), forgetBias: Float = 1.0f,
      name: String = "LSTMCell"
  ): LSTMTuple = {
    Op.createWithNameScope(name) {
      val output = input.output
      if (output.rank != 2)
        throw InvalidArgumentException(s"Input must be rank-2 (provided rank-${output.rank}).")
      if (output.shape(1) == -1)
        throw InvalidArgumentException(s"Last axis of input shape (${output.shape}) must be known.")
      val one = ops.Basic.constant(1, INT32)
      // Parameters of gates are concatenated into one multiply for efficiency.
      val cPrev = input.state._1
      val mPrev = input.state._2
      val lstmMatrix = NN.addBias(Math.matmul(Basic.concatenate(Seq(output, mPrev), axis = 1), kernel), bias)
      // i = input gate, j = new input, f = forget gate, o = output gate
      val lstmMatrixBlocks = Basic.splitEvenly(lstmMatrix, 4, axis = one)
      val (i, j, f, o) = (lstmMatrixBlocks(0), lstmMatrixBlocks(1), lstmMatrixBlocks(2), lstmMatrixBlocks(3))
      // Diagonal connections
      val forgetBiasTensor = ops.Basic.constant(forgetBias, f.dataType)
      var firstTerm = f + forgetBiasTensor
      if (wfDiag != null)
        firstTerm = firstTerm + Math.multiply(wfDiag, cPrev)
      var secondTerm = i
      if (wiDiag != null)
        secondTerm = secondTerm + Math.multiply(wiDiag, cPrev)
      var c = Math.multiply(cPrev, Math.sigmoid(firstTerm)) + Math.multiply(Math.sigmoid(secondTerm), activation(j))
      if (cellClip != -1) {
        val cellClipTensor = ops.Basic.constant(cellClip)
        c = c.clipByValue(-cellClipTensor, cellClipTensor)
      }
      var m = {
        if (woDiag != null)
          Math.multiply(activation(c), Math.sigmoid(o + Math.multiply(woDiag, c)))
        else
          Math.multiply(activation(c), Math.sigmoid(o))
      }
      // Projection
      if (projectionKernel != null) {
        m = Math.matmul(m, projectionKernel)
        if (projectionClip != -1) {
          val projectionClipTensor = ops.Basic.constant(projectionClip)
          m = m.clipByValue(-projectionClipTensor, projectionClipTensor)
        }
      }
      LSTMTuple(m, (c, m))
    }
  }

  /** @define OpDocRNNCellBasicRNNCell
    *   The `basicRNNCell` op creates an instance of the most basic RNN cell, which is defined as:
    *   `output = newState = activation(W * input + U * state + b)`.
    *
    *   Input tensors must be two-dimensional.
    *
    * @define OpDocRNNCellGRUCell
    *   The `gruCell` op creates an instance of the Gated Recurrent Unit (GRU) cell.
    *
    *   For details refer to
    *   [Learning Phrase Representations using RNN Encoder-Decoder for Statistical Machine Translation](http://arxiv.org/abs/1406.1078).
    *
    *   Input tensors must be two-dimensional.
    *
    * @define OpDocRNNCellBasicLSTMCell
    *   The `basicLSTMCell` op creates an instance of a basic Long-Short Term Memory (LSTM) cell.
    *
    *   The implementation is based on: ["Recurrent Neural Network Regularization", Zaremba et al](http://arxiv.org/abs/1409.2329).
    *
    *   We add `forgetBias` (which defaults to 1) to the biases of the forget gate in order to reduce the scale of
    *   forgetting in the beginning of training.
    *
    *   This cell does not allow for cell clipping, a projection layer, or for peep-hole connections. For advanced
    *   models, please use the full `lstmCell` op.
    *
    *   Input tensors must be two-dimensional.
    *
    * @define OpDocRNNCellLSTMCell
    *   The `lstmCell` op creates an instance of an Long-Short Term Memory (LSTM) cell.
    *
    *   The op uses optional peep-hole connections, optional cell clipping, and an optional projection layer.
    *
    *   The default non-peephole implementation is based on:
    *   ["Long Short-Term Memory", S. Hochreiter and J. Schmidhuber. Neural Computation, 9(8):1735-1780, 1997.](http://www.bioinf.jku.at/publications/older/2604.pdf).
    *
    *   The peephole implementation is based on:
    *   ["Long short-term memory recurrent neural network architectures for large scale acoustic modeling", Hasim Sak, Andrew Senior, and Francoise Beaufays. INTERSPEECH, 2014](https://research.google.com/pubs/archive/43905.pdf).
    *
    *   Input tensors must be two-dimensional.
    */
  private[ops] trait Documentation
}
