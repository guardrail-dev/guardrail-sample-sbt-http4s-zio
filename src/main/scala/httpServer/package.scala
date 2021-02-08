package example

import zio._

package object httpServer {
  type HttpServer = Has[HttpServer.Service]

  def serveForever[R](httpRoutes: org.http4s.HttpRoutes[RIO[R, *]]) = ZIO.accessM[HttpServer with R](_.get.serveForever(httpRoutes))
}

package httpServer {
  object HttpServer {
    trait Service {
      def serveForever[R](httpRoutes: org.http4s.HttpRoutes[RIO[R, *]]): RIO[R, Nothing]
    }

    val live: ULayer[HttpServer] = ZLayer.succeed(new Service {
      /**
       * Breaking out bindServer to keep noise fairly self-contained. This could consume
       * from some `Config` layer in order to access its port and host info.
       */
      def bindServer[R](httpApp: cats.data.Kleisli[RIO[R, *],org.http4s.Request[RIO[R, *]],org.http4s.Response[RIO[R, *]]]): ZManaged[R, Throwable, org.http4s.server.Server[RIO[R, *]]] = {
        import zio.interop.catz._
        import zio.interop.catz.implicits._

        import cats.effect._
        import cats.syntax.all._
        import org.http4s._
        import org.http4s.dsl.io._
        import org.http4s.implicits._
        import org.http4s.server.blaze.BlazeServerBuilder

        // Pardon the asInstanceOf, ioTimer has no way to inject R
        implicit val timer: cats.effect.Timer[RIO[R, *]] = ioTimer[Throwable].asInstanceOf[cats.effect.Timer[RIO[R, *]]]

        ZIO.runtime
          .toManaged_
          .flatMap { implicit r: Runtime[R] =>
            BlazeServerBuilder[RIO[R, *]]
              .bindHttp(8080, "localhost")
              .withHttpApp(httpApp)
              .resource
              .toManagedZIO
          }
      }

      def serveForever[R](httpRoutes: org.http4s.HttpRoutes[RIO[R, *]]): RIO[R, Nothing] = {
        import zio.interop.catz._
        import org.http4s.implicits._

        bindServer(httpRoutes.orNotFound)
          .use(_ => ZIO.never)
      }
    })
  }
}
