package co.ledger.wallet.daemon.clients

import java.util
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

import co.ledger.core
import co.ledger.core.{HttpMethod, HttpRequest, HttpUrlConnection}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.inject.Logging
import org.junit.Test
import org.scalatest.junit.AssertionsForJUnit

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

@Test
class ScalaHttpClientTest extends AssertionsForJUnit with Logging {
  implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(3))

  val mapper = new ObjectMapper() with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)

  case class DataType(param1: String, param2: Int, param3: Array[Long])

  @Test
  def testHTTPSGETRequest(): Unit = {
    val url = "https://postman-echo.com/get?foo1=bar1&foo2=bar2"
    val bodyByte = Array.emptyByteArray
    val res = awaitExecution(url, bodyByte, HttpMethod.GET)

    assert(res.isRight)
    val httpResult: HttpUrlConnection = res.right.get
    val body = new String(httpResult.readBody().getData)
    assert(httpResult.getStatusCode == 200, s"Status text is : ${httpResult.getStatusText} body is : ${body}")
    assert(mapper.readTree(body).get("args").toString == "{\"foo1\":\"bar1\",\"foo2\":\"bar2\"}")
    assert(mapper.readTree(body).get("headers").get("x-forwarded-port").textValue() == "443")
    assert(body.contains("\"user-agent\":\"ledger-lib-core\""), s"Here is the body ${body}")
  }

  @Test
  def testHTTPGETRequest(): Unit = {
    val url = "http://postman-echo.com/get?foo3=bazz&foo4=10-11-2020"
    val bodyByte = Array.emptyByteArray
    val res = awaitExecution(url, bodyByte, HttpMethod.GET)

    assert(res.isRight)
    val httpResult: HttpUrlConnection = res.right.get
    val body = new String(httpResult.readBody().getData)
    assert(httpResult.getStatusCode == 200, s"Status text is : ${httpResult.getStatusText} body is : ${body}")
    assert(mapper.readTree(body).get("args").toString == "{\"foo3\":\"bazz\",\"foo4\":\"10-11-2020\"}")
    assert(mapper.readTree(body).get("headers").get("x-forwarded-port").textValue() == "80")
    assert(body.contains("\"user-agent\":\"ledger-lib-core\""), s"Here is the body ${body}")
  }

  @Test
  def testSimplePOSTRequest(): Unit = {
    val url = "https://postman-echo.com/post?foo1=bar1&foo2=bar2"
    val sentBodyData = new DataType("val1", 12, Array[Long](1, 2, 3, 10))
    val bodyByte = mapper.writeValueAsBytes(sentBodyData)

    val res = awaitExecution(url, bodyByte, HttpMethod.POST)
    assert(res.isRight)
    val httpResult: HttpUrlConnection = res.right.get
    val body = new String(httpResult.readBody().getData)
    val returnedData = mapper.readTree(httpResult.readBody().getData).get("data").toString
    assert(returnedData == mapper.writeValueAsString(sentBodyData))
    assert(httpResult.getStatusCode == 200, s"Status text is : ${httpResult.getStatusText} body is : $body")
    info(s"Body is : $body")
    assert(body.contains("\"user-agent\":\"ledger-lib-core\""), s"Here is the body $body")
  }

  @Test
  def testPoolsAreCachedByHost(): Unit = {
    val https = "https"
    val http = "http"
    val cacheSizeStart = ScalaHttpClient.poolCacheSize
    val hostName1 = "www.google.com"
    val hostName2 = "www.yahoo.com"
    val url1 = s"https://$hostName1?aaa=bbb"
    val host1 = ScalaHttpClient.Host(hostName1, https, 443)
    // Same host different params
    val url2 = s"https://$hostName1?aaa=bbb&bbb=ccc"
    // Same host different protocol
    val url3 = s"http://$hostName1?aaa=bbb&bbb=ccc"
    val host3 = ScalaHttpClient.Host(hostName1, http, 80)

    // Same params different host
    val url4 = s"https://$hostName2?aaa=bbb&bbb=ccc"
    val host4 = ScalaHttpClient.Host(hostName2, https, 443)

    // Same host but port is different
    val portHost2 = 8080
    val url5 = s"https://$hostName2:$portHost2?aaa=bbb&bbb=ccc"
    val host5 = ScalaHttpClient.Host(hostName2, https, portHost2)



    // Check hosts are not known
    assert(!ScalaHttpClient.isHostCached(host1))
    assert(!ScalaHttpClient.isHostCached(host3))
    assert(!ScalaHttpClient.isHostCached(host4))
    assert(!ScalaHttpClient.isHostCached(host5))


    awaitExecution(url1, Array.emptyByteArray, HttpMethod.GET, 200)
    assert(ScalaHttpClient.poolCacheSize == cacheSizeStart + 1)
    assert(ScalaHttpClient.isHostCached(host1))

    awaitExecution(url2, Array.emptyByteArray, HttpMethod.GET, 200)
    assert(ScalaHttpClient.poolCacheSize == cacheSizeStart + 1)
    assert(ScalaHttpClient.isHostCached(host1))

    awaitExecution(url3, Array.emptyByteArray, HttpMethod.GET, 200)
    // An other protocol means an new connection pool
    assert(ScalaHttpClient.poolCacheSize == cacheSizeStart + 2)
    assert(ScalaHttpClient.isHostCached(host3))

    awaitExecution(url4, Array.emptyByteArray, HttpMethod.GET, 200)
    // An other host, new connection pool
    assert(ScalaHttpClient.poolCacheSize == cacheSizeStart + 3)
    assert(ScalaHttpClient.isHostCached(host4))

    awaitExecution(url5, Array.emptyByteArray, HttpMethod.GET, 200)
    // same host but new port means new connection pool
    assert(ScalaHttpClient.poolCacheSize == cacheSizeStart + 4)
    assert(ScalaHttpClient.isHostCached(host5))
  }

  private def awaitExecution(url: String, bodyByte: Array[Byte], httpMethod: HttpMethod): Either[co.ledger.core.Error, HttpUrlConnection] = {
    awaitExecution(url, bodyByte, httpMethod, 10000)
  }

  private def awaitExecution(url: String, bodyByte: Array[Byte], httpMethod: HttpMethod, timeoutMs: Long): Either[co.ledger.core.Error, HttpUrlConnection] = {
    val lock: CountDownLatch = new CountDownLatch(1)
    val resultHolder: AtomicReference[Either[core.Error, HttpUrlConnection]] = new AtomicReference()
    val req = new HttpRequestT(url, httpMethod, Map[String, String]("User-Agent" -> "ledger-lib-core", "Content-Type" -> "application/json"), bodyByte, lock, resultHolder)
    new ScalaHttpClient().execute(req)
    lock.await(timeoutMs, TimeUnit.MILLISECONDS)
    resultHolder.get()
  }

  @Test
  def testMalFormedURLIsManaged(): Unit = {
    val url = "ptt://malformedURL(^"
    val res = awaitExecution(url, Array.emptyByteArray, HttpMethod.POST)
    assert(res.isLeft)
    assert(res.left.get.getCode == core.ErrorCode.HTTP_ERROR)
  }

  class HttpRequestT(url: String,
                     method: HttpMethod, headers: Map[String, String],
                     body: Array[Byte] = Array.emptyByteArray,
                     countDownLatch: CountDownLatch,
                     result: AtomicReference[Either[core.Error, HttpUrlConnection]]) extends HttpRequest {

    override def getMethod: HttpMethod = method

    override def getHeaders: util.HashMap[String, String] = new util.HashMap(headers.asJava)

    override def getBody: Array[Byte] = body

    override def getUrl: String = url

    override def complete(httpUrlConnection: HttpUrlConnection, error: core.Error): Unit = {
      result.set(Option(httpUrlConnection).map(Right(_)).getOrElse(Left(error)))
      countDownLatch.countDown()
    }
  }


}
