package example

import zio._

package object httpServer {
  type HttpServer = Has[HttpServer.Service]

  def serveForever = ZIO.accessM[HttpServer with repository.Repository](_.get.serveForever)
}

package httpServer {
  object HttpServer {
    trait Service {
      def serveForever: RIO[repository.Repository, Nothing]
    }
  }
}
