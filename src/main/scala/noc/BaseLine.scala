package noc

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.misc._

case class BaseLineConfig(){
  val nMst = 8
  val nDDR = 4
  val mstAxiConfig = Axi4Config(
    addressWidth = 32, dataWidth = 512, idWidth = 8,
    useCache = false, useRegion = false, useProt = false
  )
  val ddrAxiConfig = mstAxiConfig.copy(idWidth = mstAxiConfig.idWidth + log2Up(nMst))
  val ddrSize = 1 GiB
  val ddrMapping = (0 until nDDR).map(i => SizeMapping(i*ddrSize, ddrSize))
}
abstract class BaseLine(val cfg: BaseLineConfig) extends Component {
  val io = new  Bundle {
    val s = Seq.fill(cfg.nMst)(slave(Axi4(cfg.mstAxiConfig)))
    val m  = Seq.fill(cfg.nDDR)(master(Axi4(cfg.ddrAxiConfig)))
  }
}

class Axi4BaseLine(cfg: BaseLineConfig) extends BaseLine(cfg) {
  val axiDemux = Seq.fill(cfg.nMst)(new Axi4Demux(cfg.mstAxiConfig, cfg.ddrMapping))
  val axiReadArb = Seq.fill(cfg.nDDR)(new Axi4ReadOnlyArbiter(cfg.ddrAxiConfig, cfg.nMst))
  val axiWriteArb = Seq.fill(cfg.nDDR)(new Axi4WriteOnlyArbiter(cfg.ddrAxiConfig, cfg.nMst, routeBufferSize = 8))

  for(i <- 0 until cfg.nMst){
    axiDemux(i).io.input << io.s(i)
  }
  for(i <- 0 until cfg.nDDR){
    axiReadArb(i).io.output >> io.m(i)
    axiWriteArb(i).io.output >> io.m(i)
  }
  for(i <- 0 until cfg.nMst){
    for(j <- 0 until cfg.nDDR){
      axiDemux(i).io.outputs(j) >> axiReadArb(j).io.inputs(i)
      axiDemux(i).io.outputs(j) >> axiWriteArb(j).io.inputs(i)
    }
  }
}