package axi.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.sim._

import scala.collection.mutable

case class DataBeat(data: BigInt, last: Boolean)

case class BPayload(resp: Int)
case class RPayload(resp: Int, data: BigInt, last: Boolean)
case class AxiTransaction(id: Int,
                          addr: Long,
                          len: Int,
                          size: Int,
                          data: Seq[BigInt] = Seq.empty,
                          resp: Int = 0){
  def driveAx(ax: Axi4Ax): Unit = {
    ax.id #= id
    ax.addr #= addr
    ax.len #= len
    ax.size #= size
    ax.lock #= 0
    ax.burst #= 1
  }
}

case class AxiMasterAgent(axi: Axi4, clockDomain: ClockDomain) {
  var arIdIssue, awIdIssue = 0
  val idUpBound = 1<<axi.config.idWidth
  val allStrb = (BigInt(1)<<axi.config.bytePerWord) - 1
  val maxSize = log2Up(axi.config.bytePerWord)
  val readCmdQueue, writeCmdQueue = mutable.Queue[AxiTransaction]()
  val writeDataQueue = mutable.Queue[DataBeat]()
  val readRob = Seq.fill(idUpBound)(mutable.Queue[RPayload]())
  val writeRob : Array[Option[BPayload]] = Array.fill(idUpBound)(None)
  val readTrQueue0, readTrQueue, writeTrQueue0, writeTrQueue = mutable.Queue[AxiTransaction]()

  axi.r.ready #= true
  axi.b.ready #= true

  def readIssueAllow = readTrQueue0.size < idUpBound
  def writeIssueAllow = writeTrQueue0.size < idUpBound

  val arDriver = StreamDriver(axi.ar, clockDomain){ ar =>
    if(readCmdQueue.nonEmpty && readIssueAllow){
      val tr = readCmdQueue.dequeue()
      readTrQueue0.enqueue(tr)
      tr.driveAx(ar)
      true
    }else{
      false
    }
  }

  val awDriver = StreamDriver(axi.aw, clockDomain){ aw =>
    if(writeCmdQueue.nonEmpty && writeIssueAllow){
      val tr = writeCmdQueue.dequeue()
      writeTrQueue0.enqueue(tr)
      tr.driveAx(aw)
      true
    }else{
      false
    }
  }

  val wDriver = StreamDriver(axi.w, clockDomain){ w =>
    if(writeDataQueue.nonEmpty){
      val tr = writeDataQueue.dequeue()
      w.data #= tr.data
      w.last #= tr.last
      w.strb #= allStrb
      true
    }else{
      false
    }
  }

  Seq(awDriver, arDriver, wDriver).foreach{d=>
    d.transactionDelay = () =>0
  }

  StreamMonitor(axi.r, clockDomain){r =>
    val id = r.id.toInt
    val d = RPayload(r.resp.toInt, r.data.toBigInt, r.last.toBoolean)
    readRob(id).enqueue(d)
  }

  StreamMonitor(axi.b, clockDomain){b =>
    val id = b.id.toInt
    writeRob(id) = Some(BPayload(b.resp.toInt))
  }

  fork {
    while (true){
      if(readTrQueue0.nonEmpty){
        val id = readTrQueue0.head.id
        if(readRob(id).nonEmpty && readRob(id).last.last){
          val data = readRob(id).dequeueAll(_ => true).map(_.data)
          val tr = readTrQueue0.dequeue().copy(data = data)
          readTrQueue.enqueue(tr)
        }else{
          clockDomain.waitSampling()
        }
      }else{
        clockDomain.waitSampling()
      }
    }
  }

  fork {
    while (true){
      if(writeTrQueue0.nonEmpty){
        val id = writeTrQueue0.head.id
        if(writeRob(id).nonEmpty){
          val tr = writeTrQueue0.dequeue()
          writeTrQueue.enqueue(tr)
        }else{
          clockDomain.waitSampling()
        }
      }else{
        clockDomain.waitSampling()
      }
    }
  }
  def asyncRead(addr: Long, len: Int): Unit = {
    val tr = AxiTransaction(id = arIdIssue, addr = addr, len = len, size = maxSize)
    readCmdQueue.enqueue(tr)
    arIdIssue = (arIdIssue + 1) % idUpBound
  }

  def asyncWrite(addr: Long, data: Seq[BigInt]): Unit = {
    val tr = AxiTransaction(id = awIdIssue, addr = addr, len = data.size-1, size = maxSize, data = data)
    writeCmdQueue.enqueue(tr)
    for(i <- data.indices){
      writeDataQueue.enqueue(DataBeat(data(i), i==data.size-1))
    }
    awIdIssue = (awIdIssue + 1) % idUpBound
  }

  def waitWriteIdle(): Unit = {
    clockDomain.waitSamplingWhere(writeCmdQueue.isEmpty)
    clockDomain.waitSamplingWhere(writeDataQueue.isEmpty)
    clockDomain.waitSamplingWhere(writeTrQueue0.isEmpty)
  }

  def waitReadIdle(): Unit = {
    clockDomain.waitSamplingWhere(readCmdQueue.isEmpty)
    clockDomain.waitSamplingWhere(readTrQueue0.isEmpty)
  }

  def waitIdle(): Unit = {
    waitReadIdle()
    waitWriteIdle()
  }
  def cancelTrReturn(): Unit = {
    fork{
      while (true){
        if(readTrQueue.nonEmpty){
          readTrQueue.dequeueAll(_ => true)
        }
        if(writeTrQueue.nonEmpty){
          writeTrQueue.dequeueAll(_ => true)
        }
        clockDomain.waitSampling()
      }
    }
  }
  // read after write test
  def rawTest(addr: Long, totalSize: Int, len: Int): Unit = {
    require(isPow2(len+1) || len==0)
    require(addr % 4096 == 0)
    val sizePerTrans = (len+1) * axi.config.bytePerWord

    for(offset <- 0 until totalSize by sizePerTrans){
      val data = (0 to len).map(i => BigInt(i*axi.config.bytePerWord + offset))
      asyncWrite(addr+offset, data)
    }
    waitWriteIdle()

    for(offset <- 0 until totalSize by sizePerTrans){
      asyncRead(addr+offset, len)
    }
    waitReadIdle()

    for(_ <- 0 until totalSize by sizePerTrans){
      val wTr = writeTrQueue.dequeue()
      val rTr = readTrQueue.dequeue()
      assert(rTr==wTr)
    }
  }

  def interleaveRawTest(mapping: Seq[SizeMapping], offset: Int, sizePerMap: Int, len: Int): Unit = {
    require(isPow2(len+1) || len==0)
    require(offset % 4096 == 0)
    val sizePerTrans = (len+1) * axi.config.bytePerWord

    for(offset1 <- 0 until sizePerMap by sizePerTrans){
      for(m <- mapping){
        val addr = m.base.toLong + offset + offset1
        val data = (0 to len).map(i => BigInt(i*axi.config.bytePerWord + addr))
        asyncWrite(addr, data)
      }
    }
    waitWriteIdle()

    for(offset1 <- 0 until sizePerMap by sizePerTrans){
      for(m <- mapping){
        val addr = m.base.toLong + offset + offset1
        asyncRead(addr, len)
      }
    }
    waitReadIdle()

    for(_ <- 0 until sizePerMap by sizePerTrans){
      for(_ <- mapping){
        val wTr = writeTrQueue.dequeue()
        val rTr = readTrQueue.dequeue()
        assert(rTr==wTr)
      }
    }
  }
}