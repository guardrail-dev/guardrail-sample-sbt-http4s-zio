package example


import zio._

package object repository {
  type Repository = Has[Repository.Service]

  /**
   * RepositoryError
   *
   * Represent a kind of tree where we have the choice to stay within a single functions'
   * error domain, or let variance widen all the way to the top.
   */
  sealed trait RepositoryError

  sealed trait GetInventoryError extends RepositoryError
  case object StockroomUnavailable extends GetInventoryError

  sealed trait PlaceOrderError extends RepositoryError
  final case class InsufficientQuantity(id: Long) extends PlaceOrderError

  sealed trait GetOrderError extends RepositoryError
  sealed trait DeleteOrderError extends RepositoryError

  final case class UnknownOrder(id: Long) extends GetOrderError with DeleteOrderError
  final case class AlreadyDeleted(id: Long) extends DeleteOrderError

  /**
   * Accessor proxies
   */
  def getInventory = ZIO.accessM[Repository](_.get.getInventory)
  def placeOrder(id: Long, petId: Long, quantity: Int) = ZIO.accessM[Repository](_.get.placeOrder(id, petId, quantity))
  def getOrder(id: Long) = ZIO.accessM[Repository](_.get.getOrder(id))
  def deleteOrder(id: Long) = ZIO.accessM[Repository](_.get.deleteOrder(id))
}

package repository {
  import example.server.definitions.Order
  object Repository {
    trait Service {
      def getInventory: IO[GetInventoryError, Map[String, Int]]
      def placeOrder(id: Long, petId: Long, quantity: Int): IO[PlaceOrderError, Order]
      def getOrder(id: Long): IO[GetOrderError, Order]
      def deleteOrder(id: Long): IO[DeleteOrderError, Unit]
    }

    /**
     * Simple in-memory implementation
     */
    val inMemory = ZLayer.fromServices[Ref[Map[String, Int]], Ref[Map[Long, Order]], Service]((inventory, orders) =>
      new Service {
        def getInventory =  inventory.get
        def placeOrder(id: Long, petId: Long, quantity: Int) =
          orders.modify { all =>
            val order = Order(id = Some(id), petId = Some(petId), quantity = Some(quantity), status = Some(Order.Status.Placed))
            (order, all + (id -> order))
          }

        def getOrder(id: Long) = for {
          currentOrders <- orders.get
          order <- ZIO.fromOption(currentOrders.get(id)).mapError(_ => UnknownOrder(id))
        } yield order
        def deleteOrder(id: Long): zio.IO[DeleteOrderError,Unit] =
          for {
            order <- orders.modify(all => (all.get(id), all - id))
            foundOrder <- ZIO.fromOption(order).mapError(_ => UnknownOrder(id))
          } yield ()
      }
    )
  }
}
