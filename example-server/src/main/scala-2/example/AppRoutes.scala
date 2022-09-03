package example

import example.server.store.{StoreHandler, StoreResource}
import org.http4s.HttpApp
import zio.{Task, ZIO, ZLayer}
import zio.interop.catz._

trait AppRoutes {
  def handler: HttpApp[Task]
}

final case class LiveAppRoutes(storeHandler: StoreHandler[Task]) extends AppRoutes {

  override def handler: HttpApp[Task] = {
    val routes = (new StoreResource[Task]).routes(storeHandler)
    routes.orNotFound
  }
}

object AppRoutes {

  val live: ZLayer[StoreHandler[Task], Nothing, AppRoutes] = ZLayer {
    for {networkRoutes <- ZIO.service[StoreHandler[Task]]} yield LiveAppRoutes(networkRoutes)
  }

}

