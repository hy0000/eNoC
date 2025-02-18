package noc

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.misc._

// Axi4 Demux depends on unique id attribute which can achieve infinite outstanding
class Axi4Demux(axiConfig: Axi4Config, decodings: Seq[SizeMapping], awPending: Int = 8) extends Component {
  val io = new Bundle {
    val input = slave(Axi4(axiConfig))
    val outputs = Vec(master(Axi4(axiConfig)), decodings.size)
  }

  val awSel = decodings.map(_.hit(io.input.aw.addr))
  val awFork = StreamFork2(io.input.aw)
  val awDe = StreamDemuxOh(awFork._1, awSel)
  val wSel = awFork._2.translateWith(awSel.asBits()).queue(awPending)
  val wHalt = io.input.w.haltWhen(!wSel.valid)
  val wDe = StreamDemuxOh(wHalt, wSel.payload.asBools)
  wSel.ready := wHalt.last && wHalt.fire

  val arSel = decodings.map(_.hit(io.input.ar.addr))
  val arDe = StreamDemuxOh(io.input.ar, arSel)

  for(i <- io.outputs.indices){
    awDe(i) >> io.outputs(i).aw
    wDe(i) >> io.outputs(i).w
    arDe(i) >> io.outputs(i).ar
  }

  io.input.r << StreamArbiterFactory().transactionLock.roundRobin.on(io.outputs.map(_.r))
  io.input.b << StreamArbiterFactory().transactionLock.roundRobin.on(io.outputs.map(_.b))
}

