package example

import zio.{ App, ZEnv }

object Launch extends App {
  def run(args: List[String]) =
    Controller
      .inMemoryProg
      .exitCode
      .provideSomeLayer[ZEnv](httpServer.HttpServer.live)
}
