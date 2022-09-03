package example.repository

import example.server.definitions.Order
import zio.{IO, Ref, UIO, ZIO, ZLayer}

sealed trait GetInventoryError

object GetInventoryError {
  case object StockroomUnavailable extends GetInventoryError
}

sealed trait PlaceOrderError

object PlaceOrderError {
  case class InsufficientQuantity(id: Long)
}

sealed trait DeleteOrderError

case class UnknownOrder(id: Long) extends DeleteOrderError

case class AlreadyDeleted(id: Long) extends DeleteOrderError


trait RepositoryService {
  def getInventory: IO[GetInventoryError, Map[String, Int]]

  def placeOrder(id: Long, petId: Long, quantity: Int): IO[PlaceOrderError, Order]

  def getOrder(id: Long): IO[UnknownOrder, Order]

  def deleteOrder(id: Long): IO[DeleteOrderError, Unit]
}


/** Simple in-memory implementation */
case class InMemoryRepositoryService(inventory: Ref[Map[String, Int]], orders: Ref[Map[Long, Order]]) extends RepositoryService {

  override def getInventory: IO[GetInventoryError, Map[String, Int]] = inventory.get

  override def placeOrder(id: Long, petId: Long, quantity: Int): IO[PlaceOrderError, Order] = orders.modify { all =>
    val order = Order(id = Some(id), petId = Some(petId), quantity = Some(quantity), status = Some(Order.Status.Placed))
    (order, all + (id -> order))
  }

  override def getOrder(id: Long): IO[UnknownOrder, Order] = for {
    currentOrders <- orders.get
    order <- ZIO.fromOption(currentOrders.get(id)).orElseFail(UnknownOrder(id))
  } yield order

  override def deleteOrder(id: Long): IO[DeleteOrderError, Unit] = for {
    order <- orders.modify(all => (all.get(id), all - id))
    foundOrder <- ZIO.fromOption(order).orElseFail(UnknownOrder(id))
  } yield ()

}

object RepositoryService {

  private val initialInventory = Map("Kibble" -> 10, "Treats" -> 3)

  private val initialOrders = Map(
    123L -> Order(id = Some(123L), petId = Some(5L), quantity = Some(3), status = Some(Order.Status.Placed))
  )

  val live: ZLayer[Any, Nothing, RepositoryService] = ZLayer {
    for {
      inventory <- zio.Ref.make[Map[String, Int]](initialInventory)
      orders <- zio.Ref.make[Map[Long, Order]](initialOrders)
    } yield InMemoryRepositoryService(inventory, orders)
  }
}
