package example.httpServer

import cats.effect.Resource
import example.AppRoutes
import example.Launch.ApplicationConfig
import org.http4s.HttpApp
import org.http4s.armeria.server.ArmeriaServerBuilder
import org.http4s.server.{Server, defaults}
import zio.*
import zio.interop.catz.*
import zio.interop.catz.implicits.*

object Http4sServerLauncher {
  def apply(routes: HttpApp[Task], atPort: Option[Int]): ZIO[Any & Scope, Throwable, Server] =
    ArmeriaServerBuilder[Task]
      .bindHttp(atPort.fold(0)(identity))
      .withHttpApp("/", routes)
      .resource.toScopedZIO

  val live: ZLayer[AppRoutes with ApplicationConfig with Scope, Throwable, Server] = ZLayer {
    for {
      config <- ZIO.service[ApplicationConfig]
      routes <- ZIO.service[AppRoutes]
      x <- apply(routes.handler, Option(config.port))
    } yield x
  }

}