package co.ledger.wallet.daemon.clients

import java.util
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

import co.ledger.core
import co.ledger.core.{HttpMethod, HttpRequest, HttpUrlConnection}
import com.twitter.inject.Logging
import org.junit.Test
import org.scalatest.junit.AssertionsForJUnit

import scala.concurrent.ExecutionContext


@Test
class ScalaHttpClientTest extends AssertionsForJUnit with Logging{
  val url = "https://postman-echo.com/get?foo1=bar1&foo2=bar2"
  val headers: util.HashMap[String, String] = new util.HashMap[String, String]()

  @Test
  def testSimpleGETRequest(): Unit = {
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor( Executors.newFixedThreadPool(3))
    val lock: CountDownLatch = new CountDownLatch(1)
    val client: ScalaHttpClient = new ScalaHttpClient()
    val result: AtomicReference[Either[core.Error, HttpUrlConnection]] = new AtomicReference()
    val req = new HttpRequestT(result, lock)
    client.execute(req)
    lock.await(30000, TimeUnit.MILLISECONDS)
    assert(result.get().isRight)
    val httpResult: HttpUrlConnection = result.get().right.get
    val body = new String(httpResult.readBody().getData)
    assert(httpResult.getStatusCode == 200, s"Status text is : ${httpResult.getStatusText} body is : ${body}")
    info((s"Body is : $body"))
    assert(body.contains("\"user-agent\":\"ledger-lib-core\""), s"Here is the body ${body}")
  }


  class HttpRequestT(result: AtomicReference[Either[core.Error, HttpUrlConnection]], countDownLatch: CountDownLatch) extends HttpRequest {

    override def getMethod: HttpMethod = HttpMethod.GET

    override def getHeaders: util.HashMap[String, String] = headers

    override def getBody: Array[Byte] = Array.emptyByteArray

    override def getUrl: String = url

    override def complete(httpUrlConnection: HttpUrlConnection, error: core.Error): Unit = {
      result.set(Option(httpUrlConnection).map(Right(_)).getOrElse(Left(error)))
      countDownLatch.countDown()
    }
  }

}
