package noc

import org.scalatest.funsuite.AnyFunSuite
class Axi4BaseLineTest extends AnyFunSuite {
  val cfg = BaseLineConfig()
  val complied = Config.sim.compile(new Axi4BaseLine(cfg))

  test("fun test 0: link test"){
    complied.doSim{dut =>
      BaseLineTestEnv(dut).linkTest()
    }
  }

  test("fun test 1: one to many"){
    complied.doSim{dut =>
      BaseLineTestEnv(dut).oneToManyTest()
    }
  }

  test("fun test 2: many to one"){
    complied.doSim{dut =>
      BaseLineTestEnv(dut).manyToOneTest()
    }
  }

  test("fun test 2: many to many"){
    complied.doSim{dut =>
      BaseLineTestEnv(dut).manyToManyTest()
    }
  }

  test("random traffic"){
    complied.doSim{dut =>
      BaseLineTestEnv(dut).randomTraffic(len = 1, n = 10000)
    }
  }

  test("reverse traffic"){
    complied.doSim{dut =>
      BaseLineTestEnv(dut).reverseTraffic(len = 1, n = 10000)
    }
  }

  test("custom0 traffic"){
    complied.doSim{dut =>
      BaseLineTestEnv(dut).custom0Traffic(len = 1, n = 3000)
    }
  }
}
