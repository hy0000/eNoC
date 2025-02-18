package noc

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axi.sim.SparseMemory
import spinal.lib.sim.StreamMonitor
import spinal.sim._

import scala.collection.mutable
import scala.util.Random

case class DDRModelConfig(maxOutstanding  : Int = 8,
                          readDelay: Int = 0,
                          writeDelay: Int = 0,
                          delayRange: Range = Range.inclusive(0, 0)
                         )
case class DDRModel(axi: Axi4, clockDomain: ClockDomain, config: DDRModelConfig){
  val memory = SparseMemory()
  val pendingCmd = new mutable.Queue[AxiJob]
  val pendingData = new mutable.Queue[BigInt]

  val busWordWidth = axi.config.dataWidth / 8
  val maxBurstSize = log2Up(busWordWidth)

  def newAxiJob(address : Long, burstLength : Int, burstSize : Int, burstType : Int, id : Long) : AxiJob = {
    AxiJob(address, burstLength, burstSize, burstType, id)
  }

  def newAxiJob(ax : Axi4Ax) : AxiJob = {
    newAxiJob(
      address = ax.addr.toLong,
      burstLength = getLen(ax),
      burstSize = getSizeAndCheck(ax),
      burstType = getBurst(ax),
      id = getId(ax)
    )
  }

  def getLen(ax : Axi4Ax):Int = {
    if(ax.config.useLen) ax.len.toInt else 0
  }
  def getSize(ax : Axi4Ax):Int = {
    if(ax.config.useSize) ax.size.toInt else maxBurstSize
  }
  def getSizeAndCheck(ax : Axi4Ax):Int = {
    val burstSize = getSize(ax)
    assert(burstSize <= maxBurstSize)
    burstSize
  }
  def getId(ax : Axi4Ax) : Long = {
    if(ax.config.useId) ax.id.toLong else 0L
  }

  def getBurst(ax : Axi4Ax) : Int = {
    if(ax.config.useBurst) ax.burst.toInt else 1
  }

  def getStrb(w : Axi4W) : BigInt = {
    if(w.config.useStrb) w.strb.toBigInt else null
  }

  def setLast(r : Axi4R, last : Boolean) : Unit = {
    if(r.config.useLast){
      r.last #= last
    }
  }

  StreamMonitor(axi.ar, clockDomain){ar =>
    newAxiJob()
  }
}

case class MyMemorySim(axi : Axi4, clockDomain : ClockDomain, config : AxiMemorySimConfig) {
  val memory = SparseMemory()
  val pending_reads = new mutable.Queue[AxiJob]
  val pending_writes = new mutable.Queue[AxiJob]
  val done_writes = new mutable.Queue[AxiJob]
  val threads = new mutable.Queue[SimThread]

  /** Bus word width in bytes */
  val busWordWidth = axi.config.dataWidth / 8
  val maxBurstSize = log2Up(busWordWidth)

  def newAxiJob(address : Long, burstLength : Int, burstSize : Int, burstType : Int, id : Long) : AxiJob = {
    AxiJob(address, burstLength, burstSize, burstType, id)
  }

  def newAxiJob(ax : Axi4Ax) : AxiJob = {
    newAxiJob(
      address = ax.addr.toLong,
      burstLength = getLen(ax),
      burstSize = getSizeAndCheck(ax),
      burstType = getBurst(ax),
      id = getId(ax)
    )
  }

  def start() : Unit = {
    threads.enqueue(fork {
      handleAr(axi.ar)
    })

    threads.enqueue(fork {
      handleR(axi.r)
    })

    if(config.useAlteraBehavior) {
      threads.enqueue(fork {
        handleAwAndW(axi.w, axi.aw, axi.b)
      })
    }
    else {
      threads.enqueue(fork {
        handleAw(axi.aw)
      })

      threads.enqueue(fork {
        handleW(axi.w)
      })

      threads.enqueue(fork {
        handleB(axi.b)
      })
    }
  }

  def stop(): Unit = {
    threads.map(f => f.terminate())
  }

  def reset(): Unit = {
    stop()
    pending_reads.clear()
    pending_writes.clear()
    start()
  }

  def getLen(ax : Axi4Ax):Int = {
    if(ax.config.useLen) ax.len.toInt else 0
  }
  def getSize(ax : Axi4Ax):Int = {
    if(ax.config.useSize) ax.size.toInt else maxBurstSize
  }
  def getSizeAndCheck(ax : Axi4Ax):Int = {
    val burstSize = getSize(ax)
    assert(burstSize <= maxBurstSize)
    burstSize
  }
  def getId(ax : Axi4Ax) : Long = {
    if(ax.config.useId) ax.id.toLong else 0L
  }

  def getBurst(ax : Axi4Ax) : Int = {
    if(ax.config.useBurst) ax.burst.toInt else config.defaultBurstType
  }

  def getStrb(w : Axi4W) : BigInt = {
    if(w.config.useStrb) w.strb.toBigInt else null
  }

  def setLast(r : Axi4R, last : Boolean) : Unit = {
    if(r.config.useLast){
      r.last #= last
    }
  }

  def handleAr(ar : Stream[Axi4Ar]) : Unit = {
    println("Handling AXI4 Master read cmds...")

    ar.ready #= false

    while(true) {
      ar.ready #= true
      clockDomain.waitSamplingWhere(ar.valid.toBoolean)
      ar.ready #= false

      pending_reads += newAxiJob(ar.payload)

      //println("AXI4 read cmd: addr=0x" + ar.payload.addr.toLong.toHexString + " count=" + (ar.payload.len.toBigInt+1))

      if(pending_reads.length >= config.maxOutstandingReads)
        clockDomain.waitSamplingWhere(pending_reads.length < config.maxOutstandingReads)
    }
  }

  def handleR(r : Stream[Axi4R]) : Unit = {
    println("Handling AXI4 Master read resp...")

    val random = Random

    r.valid #= false
    setLast(r.payload,false)

    while(true) {

      if(pending_reads.nonEmpty) {
        var job = pending_reads.front

        r.valid #= true

        var i = 0
        while(i <= job.burstLength) {
          if(config.interruptProbability > random.nextInt(100)) {
            r.valid #= false
            clockDomain.waitSampling(random.nextInt(config.interruptMaxDelay + 1))
            r.valid #= true
          }

          if(i == job.burstLength)
            setLast(r.payload,true)
          if(r.config.useId)
            r.payload.id   #= job.id
          if(r.config.useResp)
            r.payload.resp #= 0

          r.payload.data #= memory.readBigInt(job.alignedBurstAddress(i, maxBurstSize), busWordWidth)
          clockDomain.waitSamplingWhere(r.ready.toBoolean)
          i = i + 1
        }

        r.valid #= false
        setLast(r.payload,false)

        pending_reads.dequeue()

        //println("AXI4 read rsp: addr=0x" + job.address.toLong.toHexString + " count=" + (job.burstLength+1))
      } else {
        clockDomain.waitSampling(1)
      }
    }
  }

  def handleAw(aw : Stream[Axi4Aw]) : Unit = {
    println("Handling AXI4 Master write cmds...")

    aw.ready #= false

    while(true) {
      aw.ready #= true
      clockDomain.waitSamplingWhere(aw.valid.toBoolean)
      aw.ready #= false

      pending_writes += newAxiJob(aw.payload)

      //println("AXI4 write cmd: addr=0x" + aw.payload.addr.toLong.toHexString + " count=" + (aw.payload.len.toBigInt+1))

      if(pending_writes.length >= config.maxOutstandingWrites)
        clockDomain.waitSamplingWhere(pending_writes.length < config.maxOutstandingWrites)
    }
  }

  def handleW(w : Stream[Axi4W]) : Unit = {
    println("Handling AXI4 Master write...")

    w.ready #= false

    while(true) {
      if(pending_writes.nonEmpty) {
        val job = pending_writes.front

        w.ready #= true

        for(i <- 0 to job.burstLength) {
          clockDomain.waitSamplingWhere(w.valid.toBoolean)
          memory.writeBigInt(job.alignedBurstAddress(i, maxBurstSize), w.payload.data.toBigInt, busWordWidth, getStrb(w.payload))
        }

        w.ready #= false

        done_writes.enqueue(job)
        pending_writes.dequeue()

        //println("AXI4 write: addr=0x" + job.address.toLong.toHexString + " count=" + (job.burstLength+1))
      }else{
        clockDomain.waitSampling()
      }
    }
  }

  def handleB(b : Stream[Axi4B]): Unit = {
    b.valid #= false

    while (true){
      if(done_writes.nonEmpty){
        val job = done_writes.dequeue()
        b.valid #= true
        if(b.config.useId)
          b.payload.id #= job.id
        if(b.config.useResp)
          b.payload.resp #= 0
        clockDomain.waitSamplingWhere(b.ready.toBoolean)
        b.valid #= false
      }else{
        clockDomain.waitSampling()
      }
    }
  }

  /**
   * Handle write command, write, and write response channel as implemented
   * by Altera/Intel on their Cyclone 5 platform.
   * Their implementation behaves as all three channels are coupled. The
   * implementation waits until all words for a write operation have been
   * transfered. Then it asserts the AWREADY to accept the write command.
   * After that, BVALID is asserted.
   *
   * @param w  AXI write channel
   * @param aw AXI write command channel
   * @param b  AXI write response channel
   */
  def handleAwAndW(w : Stream[Axi4W], aw : Stream[Axi4Aw], b : Stream[Axi4B]) : Unit = {
    println("Handling AXI4 Master write cmds and write (Altera/Intel behavior)...")

    val random = Random

    aw.ready #= false
    w.ready  #= false
    b.valid  #= false

    while(true) {
      clockDomain.waitSamplingWhere(aw.valid.toBoolean && w.valid.toBoolean)
      w.ready #= true

      assert(
        assertion = (aw.payload.len.toBigInt + (aw.payload.addr.toBigInt & 4095)) <= 4095,
        message   = s"Write request crossing 4k boundary (addr=${aw.payload.addr.toBigInt.toString(16)}, len=${aw.payload.len.toLong.toHexString}"
      )

      val job = newAxiJob(aw.payload)

      var i = 0;
      while(i <= aw.payload.len.toInt) {
        if(config.interruptProbability > random.nextInt(100)) {
          w.ready #= false
          clockDomain.waitSampling(random.nextInt(config.interruptMaxDelay + 1))
          w.ready #= true
        }
        else {
          clockDomain.waitSamplingWhere(w.valid.toBoolean)
          memory.writeBigInt(job.alignedBurstAddress(i, maxBurstSize), w.payload.data.toBigInt, busWordWidth, getStrb(w.payload))
          i = i + 1
        }
      }

      aw.ready #= true

      clockDomain.waitSampling(1)

      aw.ready #= false
      w.ready #= false

      // Handle write response
      clockDomain.waitSampling(config.writeResponseDelay)

      b.valid #= true
      if(b.config.useId)
        b.payload.id #= job.id
      if(b.config.useResp)
        b.payload.resp #= 0
      clockDomain.waitSamplingWhere(b.ready.toBoolean)
      b.valid #= false

      //println("AXI4 write cmd: addr=0x" + aw.payload.addr.toLong.toHexString + " count=" + (aw.payload.len.toBigInt+1))
    }
  }
}

case class AxiJob (
                    address     : Long,
                    burstLength : Int,
                    burstSize   : Int,
                    burstType   : Int,
                    id          : Long,
                    write       : Boolean
                  ) {
  val dataTransactionSize : Int  = (1 + burstLength) << burstSize
  val lowerWrapBoundary   : Long = (address / dataTransactionSize) * dataTransactionSize
  val upperWrapBoundary   : Long = lowerWrapBoundary + dataTransactionSize
  def incrAddress(i : Int): Long = ((address >> burstSize) + i) << burstSize
  def wrapAddress(i : Int): Long = {
    val ret = incrAddress(i)
    if(ret >= upperWrapBoundary){
      ret - dataTransactionSize
    }
    else ret
  }
  // check for read/write over 4k boundary
  if(burstType == 1){
    assert(
      assertion = ((burstLength << burstSize) + (address & 4095)) <= 4095,
      message   = s"Read/write request crossing 4k boundary (addr=${address.toHexString}, len=${burstLength.toLong.toHexString}"
    )
  }
  def burstAddress(i : Int):Long = {
    val ret = burstType match {
      case 0 => address        // FIXED
      case 1 => incrAddress(i) // INCR
      case 2 => wrapAddress(i) // WRAP
    }
    ret
  }
  def alignedBurstAddress(i : Int, maxBurstSize : Int):Long = {
    (burstAddress(i) >> maxBurstSize) << maxBurstSize
  }
}