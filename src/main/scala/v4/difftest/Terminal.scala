package boom.v4.difftest

import chisel3._
import chisel3.util._

/** A native simulation policy, explicitly armed at run setup. Only an actual
  * architectural canonical FENCE.I retirement can stop issue. The unique
  * instruction's existing pipeline flush closes earlier speculative work;
  * cache and transport completion remain separate observer obligations.
  */
class DifftestTerminalObservation extends Bundle
{
  val armed = Bool()
  val configured_pc = UInt(64.W)
  val reached = Bool()
  val marker = new DifftestInstruction
  val source_cycle = UInt(64.W)
  val stopped = Bool()
  val core_empty = Bool()
  val writeback_active = Bool()
  val interrupt_pending = Bool()
}

class DifftestTerminalStop(width: Int) extends Module
{
  require(width > 0)
  val io = IO(new Bundle {
    val armed = Input(Bool())
    val terminal_pc = Input(UInt(64.W))
    val cycle = Input(UInt(64.W))
    val retire = Input(Vec(width, Valid(new DifftestInstruction)))
    val core_empty = Input(Bool())
    val writeback_active = Input(Bool())
    val interrupt_pending = Input(Bool())
    // The combinational retiring-marker term prevents a next-PC restart in
    // the same edge that transitions the ROB out of its unique-instruction wait.
    val stop_issue = Output(Bool())
    val observation = Output(new DifftestTerminalObservation)
  })

  val stopped = RegInit(false.B)
  val marker = RegInit(0.U.asTypeOf(new DifftestInstruction))
  val matching = VecInit(io.retire.map { retirement =>
    io.armed && retirement.valid && retirement.bits.architectural &&
      retirement.bits.pc === io.terminal_pc && retirement.bits.instruction === "h0000100f".U
  })
  val retiring_marker = matching.asUInt.orR
  when (retiring_marker) {
    assert(!stopped, "DiffTest terminal marker must retire exactly once")
    assert(PopCount(matching) === 1.U, "DiffTest terminal marker must have one retiring identity")
    assert(PopCount(io.retire.map(_.valid)) === 1.U,
      "DiffTest terminal FENCE.I must retire as a unique instruction")
    stopped := true.B
    marker := Mux1H(matching, io.retire.map(_.bits))
  }
  when (stopped) {
    assert(!io.retire.map(_.valid).reduce(_ || _), "DiffTest core retired after its terminal marker")
  }

  io.stop_issue := stopped || retiring_marker
  io.observation.armed := io.armed
  io.observation.configured_pc := io.terminal_pc
  io.observation.reached := stopped
  io.observation.marker := marker
  io.observation.source_cycle := io.cycle
  io.observation.stopped := stopped
  io.observation.core_empty := io.core_empty
  io.observation.writeback_active := io.writeback_active
  io.observation.interrupt_pending := io.interrupt_pending
}
