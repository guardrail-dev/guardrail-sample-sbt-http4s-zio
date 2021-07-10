package example.tests

import zio._
import zio.console._
import zio.interop.catz._
import zio.test._
import zio.test.Assertion._
import zio.test.environment._

import java.io.IOException

import example.Controller
import example.client.store.StoreClient
import example.repository.Repository

import example.client.store.GetOrderByIdResponse
import example.client.definitions.Order
import example.server.definitions.{ Order => ServerOrder }
import example.server.store.{ GetOrderByIdResponse => ServerGetOrderByIdResponse }

import org.http4s.client.Client

object RoundTripSpec extends DefaultRunnableSpec {
  val initialInventory = Map(
    "Kibble" -> 10,
    "Treats" -> 3
  )
  val inventoryLayer = Ref.make(initialInventory).toManaged_.toLayer

  val initialOrders = Map(
    123L -> ServerOrder(id = Some(123L), petId = Some(5L), quantity = Some(3), status = Some(ServerOrder.Status.Placed))
  )
  val ordersLayer = Ref.make(initialOrders).toManaged_.toLayer
  val inMemoryLayer = (inventoryLayer ++ ordersLayer) >>> example.repository.Repository.inMemory

  val buildStaticClient = for {
    combinedRoutes <- Controller.combineRoutes
  } yield StoreClient.httpClient(Client.fromHttpApp[RIO[Repository, *]](combinedRoutes), "http://localhost")

  def spec = suite("RoundTripSpec")(
    /**
     * This test hits the StoreHandler directly, bypassing all of the routing infrastructure in http4s
     * This is useful for testing logic without the overhead of HTTP encoding and decoding,
     * possibly in a situation where your tests are significantly slowed down by the round-trip through http4s.
     */
    testM("Test controller functions without the client") {
      for {
        res <- Controller.handler.getOrderById(ServerGetOrderByIdResponse)(123L)
      } yield assert(res)(equalTo(ServerGetOrderByIdResponse.Ok(ServerOrder(id=Some(123), petId=Some(5), quantity=Some(3), status=Some(ServerOrder.Status.Placed)))))
    },
    /**
     * Build a simple client, then hit the endpoint.
     * This is just a sanity check to ensure our tests are wired up correctly against the inMemoryLayer
     */
    testM("getOrderById can find values in the static, in-memory repository") {
      for {
        staticClient <- buildStaticClient
        res <- staticClient.getOrderById(123L)
      } yield assert(res)(equalTo(GetOrderByIdResponse.Ok(Order(id=Some(123), petId=Some(5), quantity=Some(3), status=Some(Order.Status.Placed)))))
    },
    /**
     * A negative test, to ensure that we get a NotFound value back, instead of an exception.
     */
    testM("getOrderById correctly returns NotFound for incorrect ids") {
      for {
        staticClient <- buildStaticClient
        res <- staticClient.getOrderById(404L)
      } yield assert(res)(equalTo(GetOrderByIdResponse.NotFound))
    },
    /**
     * Test mutating the in-memory Repository.
     * This is a multi-phase test,
     * - verifying the negative case,
     * - mutating the store,
     * - then verifying the positive case.
     */
    testM("placeOrder followed by getOrder") {
      val myOrder = Order(id=Some(5), petId=Some(6), quantity=Some(1), status=Some(Order.Status.Placed))
      for {
        staticClient <- buildStaticClient
        first <- staticClient.getOrderById(5)
        placedResponse <- staticClient.placeOrder(Some(myOrder))
        second <- staticClient.getOrderById(5)
      } yield (
        assert(first)(equalTo(GetOrderByIdResponse.NotFound)) &&
        assert(second)(equalTo(GetOrderByIdResponse.Ok(myOrder)))
      )
    },
  ).provideSomeLayer[ZEnv](inMemoryLayer)
}
