package boom.v4.difftest

import chisel3._
import chisel3.util._

// BOOM-private source descriptions, never public transport enum values.
object DifftestLoadSource
{
  def unavailable = 0.U(3.W)
  def sram = 1.U(3.W)
  def storePipeline = 2.U(3.W)
  def storeQueue = 3.U(3.W)
  def lineBuffer = 4.U(3.W)
  def uncached = 5.U(3.W)
}

class DifftestQueueAllocation extends Bundle
{
  val identity = new DifftestIdentity
  val source_cycle = UInt(64.W)
  val index = UInt(32.W)
  val load = Bool()
}

class DifftestAttempt extends Bundle
{
  val identity = new DifftestIdentity
  val source_cycle = UInt(64.W)
  val address = UInt(64.W)
  val queue_index = UInt(32.W)
}

class DifftestLoadSample extends Bundle
{
  val identity = new DifftestIdentity
  val queue_index = UInt(32.W)
  val mem_size = UInt(2.W)
  val source_cycle = UInt(64.W)
  val source_valid = Bool()
  val source_kind = UInt(3.W)
  val address = UInt(64.W)
  // Word before LoadGen's sign/zero extension. Mask is relative to this word.
  val raw_word = UInt(64.W)
  val byte_mask = UInt(8.W)
  val writer_mask = UInt(8.W)
  val writers = Vec(8, new DifftestIdentity)
}

class DifftestStore extends Bundle
{
  val identity = new DifftestIdentity
  val queue_index = UInt(32.W)
  val mem_size = UInt(2.W)
  val source_cycle = UInt(64.W)
  val address = UInt(64.W)
  val word = UInt(64.W)
  val byte_mask = UInt(8.W)
  val atomic = Bool()
  val command = UInt(5.W)
}

class DifftestLsuObservation(width: Int, dispatchWidth: Int) extends Bundle
{
  val allocate = Vec(dispatchWidth, Valid(new DifftestQueueAllocation))
  val begin = Vec(width, Valid(new DifftestAttempt))
  val cancel = Vec(width, Valid(new DifftestAttempt))
  val nack = Vec(width, Valid(new DifftestAttempt))
  val forward = Vec(width, Valid(new DifftestLoadSample))
  val complete = Vec(width, Valid(new DifftestLoadSample))
  val local_store = Vec(dispatchWidth, Valid(new DifftestStore))
  val retire_load = Vec(dispatchWidth, Valid(new DifftestAttempt))
  val retire_load_data = Vec(dispatchWidth, UInt(64.W))
  // These are lifecycle facts, not global publications.
  val store_ack = Vec(width, Valid(new DifftestAttempt))
  val store_release = Valid(new DifftestAttempt)
  val queues_empty = Bool()
}

class DifftestCacheObservation(width: Int) extends Bundle
{
  val load = Vec(width, Valid(new DifftestLoadSample))
  // An exclusive-cache data write is the candidate publication fact. The host
  // still needs the common clock/arbitration proof before assigning a GMO rank.
  val store_applied = Valid(new DifftestStore)
  val idle = Bool()
}
