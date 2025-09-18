package com.example.echo

import com.example.echo.thrift.Echo
import com.twitter.conversions.DurationOps._
import com.twitter.finagle.{ListeningServer, RequestTimeoutException, ThriftMux}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.util.{Await, Closable, Future, Timer}
import java.net.InetSocketAddress
import org.scalatest.BeforeAndAfterAll
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

object EchoHandlers {
  def success: Echo.MethodPerEndpoint = new Echo.MethodPerEndpoint {
    override def ping(msg: String): Future[String] = Future.value(s"echo:$msg")
  }

  def serverFailure: Echo.MethodPerEndpoint = new Echo.MethodPerEndpoint {
    override def ping(msg: String): Future[String] =
      Future.exception(new IllegalArgumentException("bad request"))
  }

  def delayed(delayMs: Long)(implicit timer: Timer): Echo.MethodPerEndpoint = new Echo.MethodPerEndpoint {
    override def ping(msg: String): Future[String] =
      Future.sleep(delayMs.milliseconds).map(_ => s"late:$msg")
  }
}

class EchoClientSpec
    extends AnyFunSuite
    with BeforeAndAfterAll
    with BeforeAndAfterEach {

  private implicit val timer: Timer = DefaultTimer

  private val mutableService = new MutableEchoService(EchoHandlers.success)
  private var server: ListeningServer = _
  private var client: Echo.MethodPerEndpoint = _
  private var clientCloser: Closable = Closable.nop

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    server = ThriftMux.server.serveIface("localhost:0", mutableService)
    val address = server.boundAddress.asInstanceOf[InetSocketAddress]
    val servicePerEndpoint = ThriftMux.client.servicePerEndpoint[Echo.ServicePerEndpoint](s"localhost:${address.getPort}", label = "echo-client")
    client = Echo.MethodPerEndpoint(servicePerEndpoint)
    clientCloser = servicePerEndpoint.asClosable
  }

  override protected def afterAll(): Unit = {
    try {
      Await.result(clientCloser.close())
      Await.result(server.close())
    } finally {
      super.afterAll()
    }
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    mutableService.become(EchoHandlers.success)
  }

  test("client reflects latest handler responses") {
    mutableService.become(new Echo.MethodPerEndpoint {
      override def ping(msg: String): Future[String] = Future.value(s"pong:$msg")
    })

    val response = Await.result(client.ping("hello"))
    assert(response == "pong:hello")

    mutableService.become(new Echo.MethodPerEndpoint {
      override def ping(msg: String): Future[String] = Future.value(s"goodbye:$msg")
    })

    val updatedResponse = Await.result(client.ping("hello"))
    assert(updatedResponse == "goodbye:hello")
  }

  test("client propagates server-side exceptions") {
    mutableService.become(EchoHandlers.serverFailure)

    val ex = intercept[Exception] {
      Await.result(client.ping("boom"))
    }

    assert(ex.getMessage.contains("bad request"))
  }

  test("client honors request timeouts independent of server handler") {
    mutableService.become(EchoHandlers.delayed(200))

    val address = server.boundAddress.asInstanceOf[InetSocketAddress]
    val servicePerEndpoint = ThriftMux.client
      .withRequestTimeout(100.milliseconds)
      .servicePerEndpoint[Echo.ServicePerEndpoint](s"localhost:${address.getPort}", label = "echo-timeout-client")
    val timeoutClient = Echo.MethodPerEndpoint(servicePerEndpoint)

    try {
      intercept[RequestTimeoutException] {
        Await.result(timeoutClient.ping("slow"))
      }
    } finally {
      Await.result(servicePerEndpoint.asClosable.close())
    }
  }
}
