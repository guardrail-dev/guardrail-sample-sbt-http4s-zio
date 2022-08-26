package example.tests

import zio.*
import zio.interop.catz.*

import java.io.IOException
import example.client.store.StoreClient
import example.client.store.GetOrderByIdResponse
import example.client.definitions.Order
import example.server.definitions.Order as ServerOrder
import org.http4s.armeria.client.ArmeriaClient

import scala.concurrent.duration.*
import org.http4s.client.Client
import zio.test.Assertion.equalTo
import zio.test.*

import java.net.URI

object RoundTripSpec extends ZIOSpecDefault {

  import org.http4s.armeria.client.ArmeriaClientBuilder

  val client: Client[Task] =
    ArmeriaClient.apply()

  override def spec: Spec[TestEnvironment with Scope, Any]  = suite("RoundTripSpec")(
    /**
     * This test hits the StoreHandler directly, bypassing all of the routing infrastructure in http4s
     * This is useful for testing logic without the overhead of HTTP encoding and decoding,
     * possibly in a situation where your tests are significantly slowed down by the round-trip through http4s.
     */
    test("Test controller functions without the client") {
      for {
        res <- StoreClient.httpClient(client, "http://localhost:8080").getOrderById(123L)
      } yield assert(res)(equalTo(GetOrderByIdResponse.Ok(Order(id = Some(123), petId = Some(5), quantity = Some(3), status = Some(Order.Status.Placed)))))
    },

    /**
     * Build a simple client, then hit the endpoint.
     * This is just a sanity check to ensure our tests are wired up correctly against the inMemoryLayer
     */
//    test("getOrderById can find values in the static, in-memory repository") {
//      for {
//        res <- StoreClient.httpClient(client, "").getOrderById(123L)
//      } yield assert(res)(equalTo(GetOrderByIdResponse.Ok(Order(id = Some(123), petId = Some(5), quantity = Some(3), status = Some(Order.Status.Placed)))))
//    },

    /**
     * A negative test, to ensure that we get a NotFound value back, instead of an exception.
     */
//    test("getOrderById correctly returns NotFound for incorrect ids") {
//      for {
//        res <- StoreClient.httpClient(client, "").getOrderById(404L)
//      } yield assert(res)(equalTo(GetOrderByIdResponse.NotFound))
//    },

    /**
     * Test mutating the in-memory Repository.
     * This is a multi-phase test,
     * - verifying the negative case,
     * - mutating the store,
     * - then verifying the positive case.
     */
//    test("placeOrder followed by getOrder") {
//      val myOrder = Order(id = Some(5), petId = Some(6), quantity = Some(1), status = Some(Order.Status.Placed))
//      for {
//        first <- StoreClient.httpClient(client, "").getOrderById(5)
//        placedResponse <- StoreClient.httpClient(client, "").placeOrder(Some(myOrder))
//        second <- StoreClient.httpClient(client, "").getOrderById(5)
//      } yield (
//        assert(first)(equalTo(GetOrderByIdResponse.NotFound)) &&
//          assert(second)(equalTo(GetOrderByIdResponse.Ok(myOrder)))
//        )
//    }
  ).provide()
}
