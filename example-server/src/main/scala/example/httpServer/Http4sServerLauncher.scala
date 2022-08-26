package example.httpServer

import cats.effect.Resource
import example.AppRoutes
import example.Launch.ApplicationConfig
import org.http4s.HttpApp
import org.http4s.armeria.server.ArmeriaServerBuilder
import org.http4s.server.Server
import zio.*
import zio.interop.catz.*
import zio.interop.catz.implicits.*

object Http4sServerLauncher {
  def apply(routes: HttpApp[Task], atPort: Int): ZIO[Any & Scope, Throwable, Server] =
    ArmeriaServerBuilder[Task]
      .bindHttp(atPort)
      .withHttpApp("/", routes)
      .resource.toScopedZIO

  val live: ZLayer[AppRoutes with ApplicationConfig with Scope, Throwable, Server] = ZLayer {
    for {
      config <- ZIO.service[ApplicationConfig]
      routes <- ZIO.service[AppRoutes]
      x <- apply(routes.handler, config.port)
    } yield x
  }

}