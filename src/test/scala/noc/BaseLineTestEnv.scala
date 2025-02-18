package noc

import axi.sim.AxiMasterAgent
import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._
import spinal.sim.SimThread

import scala.collection.mutable
import scala.util.Random

object BaseLineTestEnv {
  def apply(dut: BaseLine): BaseLineTestEnv = {
    dut.clockDomain.forkStimulus(10)
    new BaseLineTestEnv(dut)
  }
}
class BaseLineTestEnv(dut: BaseLine) {
  val sizePerWorld = dut.io.s.head.config.bytePerWord
  val maxLen = ((4 KiB) / sizePerWorld).toInt - 1

  val ddrConfig = AxiMemorySimConfig(
    maxOutstandingReads = 100,
    maxOutstandingWrites = 100,
    interruptProbability = 100,
    interruptMaxDelay = sizePerWorld / 16
  )

  val mstAgt = dut.io.s.map(axi => AxiMasterAgent(axi, dut.clockDomain))
  val ddr = dut.io.m.map(axi => MyMemorySim(axi, dut.clockDomain, ddrConfig))
  ddr.foreach(_.start())

  def linkTest(): Unit = {
    val lenSeq = Seq(0, 1, maxLen)
    for(len <- lenSeq){
      for(mst <- mstAgt){
        for(map <- dut.cfg.ddrMapping){
          mst.rawTest(map.base.toLong, (8 KiB).toInt, len)
        }
      }
    }
  }

  def oneToManyTest(): Unit = {
    val lenSeq = Seq(0, 1, maxLen)
    for(len <- lenSeq){
      for((mst, i) <- mstAgt.zipWithIndex){
        mst.interleaveRawTest(dut.cfg.ddrMapping, (8 KiB).toInt*i, (8 KiB).toInt, len)
      }
    }
  }

  def manyToOneTest(): Unit = {
    val lenSeq = Seq(0, 1, maxLen)
    for(len <- lenSeq){
      for(map <- dut.cfg.ddrMapping){
        val jobQueue = mutable.Queue[SimThread]()
        for((mst, i) <- mstAgt.zipWithIndex){
          val job = fork {
            val addr = map.base.toLong + (8 KiB).toInt*i
            mst.rawTest(addr, (8 KiB).toInt, len)
          }
          jobQueue.enqueue(job)
        }
        jobQueue.foreach(_.join())
      }
    }
  }

  def manyToManyTest(): Unit = {
    val lenSeq = Seq(0, 1, maxLen)
    for(len <- lenSeq){
      val jobQueue = mutable.Queue[SimThread]()
      for((mst, i) <- mstAgt.zipWithIndex){
        val job = fork {
          mst.interleaveRawTest(dut.cfg.ddrMapping, (8 KiB).toInt*i, (8 KiB).toInt, len)
        }
        jobQueue.enqueue(job)
      }
      jobQueue.foreach(_.join())
    }
  }

  def genRandomData(): BigInt = BigInt(dut.cfg.mstAxiConfig.bytePerWord*8, Random)

  def traffic(len: Int = 0, n: Int)(route: Int => Int): Unit = {
    val jobQueue = mutable.Queue[SimThread]()
    for((mst, i) <- mstAgt.zipWithIndex){
      mst.cancelTrReturn()
      val job = fork {
        for(_ <- 0 until n){
          val ddrId = route(i)
          val addr = dut.cfg.ddrMapping(ddrId).randomPick(sizePerWorld*(len+1), aligned = true).toLong
          if(Random.nextInt(2)==0){
            val data = Seq.fill(len+1)(genRandomData())
            mst.asyncWrite(addr, data)
          }else{
            mst.asyncRead(addr, len)
          }
        }
        mst.waitIdle()
      }
      jobQueue.enqueue(job)
    }
    jobQueue.foreach(_.join())
  }

  def randomTraffic(len: Int, n: Int): Unit = {
    traffic(len, n){_ =>Random.nextInt(dut.cfg.nDDR) }
  }

  def reverseTraffic(len: Int, n: Int): Unit = {
    traffic(len, n){src => dut.cfg.nDDR - 1 - (src % dut.cfg.nDDR) }
  }

  def custom0Traffic(len: Int, n: Int): Unit = {
    traffic(len, n){src => if(src==0) Random.nextInt(dut.cfg.nDDR) else 0}
  }
}