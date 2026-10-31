package boom.v4.difftest

import chisel3._
import chisel3.util._

/** Private BOOM observations. The harness supplies the session epoch and source
  * clock; these records do not assign a cross-hart memory-order rank.
  */
class DifftestControl extends Bundle
{
  val epoch = UInt(64.W)
  val cycle = UInt(64.W)
}

class DifftestIdentity extends Bundle
{
  val epoch = UInt(64.W)
  val uid = UInt(64.W)
  val rob_generation = UInt(64.W)
  val memory_generation = UInt(64.W)
  val attempt = UInt(32.W)
}

/** Allocate once per dispatch handshake, including partial dispatch groups. */
class DifftestIdentityAllocator(width: Int, entries: Int) extends Module
{
  require(width > 0 && entries > 0)
  val io = IO(new Bundle {
    val epoch = Input(UInt(64.W))
    val allocate = Input(Vec(width, Bool()))
    val slots = Input(Vec(width, UInt(log2Ceil(entries).max(1).W)))
    val identities = Output(Vec(width, new DifftestIdentity))
  })
  val next_uid = RegInit(1.U(64.W))
  val generations = RegInit(VecInit(Seq.fill(entries)(0.U(64.W))))
  val count = PopCount(io.allocate)
  when (count =/= 0.U) {
    assert(next_uid <= ((BigInt(1) << 64) - 1).U - count, "DiffTest UID exhausted")
    next_uid := next_uid + count
  }
  for (w <- 0 until width) {
    val offset = if (w == 0) 0.U else PopCount(io.allocate.take(w))
    io.identities(w).epoch := io.epoch
    io.identities(w).uid := next_uid + offset
    io.identities(w).rob_generation := generations(io.slots(w)) + 1.U
    io.identities(w).attempt := 0.U
    io.identities(w).memory_generation := 0.U
    when (io.allocate(w)) {
      assert(io.slots(w) < entries.U, "DiffTest ROB slot out of range")
      assert(!generations(io.slots(w)).andR, "DiffTest ROB generation exhausted")
      generations(io.slots(w)) := generations(io.slots(w)) + 1.U
    }
    for (other <- 0 until w) {
      assert(!(io.allocate(w) && io.allocate(other) && io.slots(w) === io.slots(other)),
        "DiffTest dispatch allocated the same ROB slot twice")
    }
  }
}

class DifftestInstruction extends Bundle
{
  val identity = new DifftestIdentity
  val source_cycle = UInt(64.W)
  val pc = UInt(64.W)
  val instruction = UInt(32.W)
  val rob_index = UInt(32.W)
  val ldq_index = UInt(32.W)
  val stq_index = UInt(32.W)
  val uses_ldq = Bool()
  val uses_stq = Bool()
  val atomic = Bool()
  val mem_command = UInt(5.W)
  val mem_size = UInt(2.W)
  // A physical retirement may be a predicated-off SFB instruction.
  val architectural = Bool()
  val destination = UInt(5.W)
  val destination_type = UInt(2.W)
  val write_data = UInt(64.W)
}

class DifftestSquash extends Bundle
{
  val identity = new DifftestIdentity
  val source_cycle = UInt(64.W)
  val rob_index = UInt(32.W)
  val rollback = Bool()
}

class DifftestTrap extends Bundle
{
  val identity = new DifftestIdentity
  val source_cycle = UInt(64.W)
  val pc = UInt(64.W)
  val cause = UInt(64.W)
}

// Carries the physical CSRFile value one edge after either a stable CSR
// retirement or an accepted counter write. The owner UID is explicit;
// unknown CSRs receive no comparison certificate.
class DifftestCsrPostObservation extends Bundle {
  val identity = new DifftestIdentity
  val source_cycle = UInt(64.W)
  val pc = UInt(64.W)
  val address = UInt(12.W)
  val value = UInt(64.W)
}

class DifftestCoreObservation(width: Int, entries: Int) extends Bundle
{
  val hartid = UInt(64.W)
  val allocate = Vec(width, Valid(new DifftestInstruction))
  val retire = Vec(width, Valid(new DifftestInstruction))
  val squash = Vec(entries, Valid(new DifftestSquash))
  val trap = Valid(new DifftestTrap)
  val terminal = new DifftestTerminalObservation
  // An unretired WFI is a quiescent terminal boundary only with a live,
  // unique ROB owner and an active CSR WFI stall, never a fabricated retire.
  val wfi_park = new DifftestWfiParkObservation
  val csr_post = Valid(new DifftestCsrPostObservation)
  // Captured immediately after an accepted CSR write, then matched by UID at retirement.
  val csr_write_post = Valid(new DifftestCsrPostObservation)
}
