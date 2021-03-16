package example

import zio._

package object httpServer {
  type HttpServer = Has[HttpServer.Service]

  def bindServer[R](httpApp: org.http4s.Http[RIO[R, *], RIO[R, *]]) = ZIO.access[HttpServer with R](_.get.bindServer(httpApp))
}

package httpServer {
  object HttpServer {
    trait Service {
      def bindServer[R](httpApp: org.http4s.Http[RIO[R, *], RIO[R, *]]): ZManaged[R, Throwable, org.http4s.server.Server[RIO[R, *]]]
    }

    val live: ULayer[HttpServer] = ZLayer.succeed(new Service {
      /**
       * Breaking out bindServer to keep noise fairly self-contained. This could consume
       * from some `Config` layer in order to access its port and host info.
       */
      def bindServer[R](httpApp: org.http4s.Http[RIO[R, *], RIO[R, *]]): ZManaged[R, Throwable, org.http4s.server.Server[RIO[R, *]]] = {
        import zio.interop.catz._
        import zio.interop.catz.implicits._

        import cats.effect._
        import cats.syntax.all._
        import org.http4s._
        import org.http4s.dsl.io._
        import org.http4s.implicits._
        import org.http4s.server.blaze.BlazeServerBuilder

        // Pardon the asInstanceOf, ioTimer has no way to inject R
        implicit val timer: cats.effect.Timer[RIO[R, *]] = ioTimer[R, Throwable]

        ZIO.runtime
          .toManaged_
          .flatMap { implicit r: Runtime[R] =>
            BlazeServerBuilder[RIO[R, *]](scala.concurrent.ExecutionContext.global)
              .bindHttp(8080, "localhost")
              .withHttpApp(httpApp)
              .resource
              .toManagedZIO
          }
      }
    })
  }
}
