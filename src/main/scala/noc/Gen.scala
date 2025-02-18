package noc

object Gen extends App {
  val cfg = BaseLineConfig()
  Config.spinal.generateVerilog(new Axi4BaseLine(cfg))
}
