package co.ledger.wallet.daemon.clients

import java.io.{BufferedInputStream, ByteArrayInputStream, DataOutputStream, OutputStream}
import java.net.{HttpURLConnection, InetSocketAddress, URL}
import java.util

import co.ledger.core._
import co.ledger.wallet.daemon.configurations.DaemonConfiguration
import co.ledger.wallet.daemon.exceptions.InvalidUrlException
import com.google.common.cache.{CacheBuilder, CacheLoader, LoadingCache}
import com.sun.xml.internal.messaging.saaj.util.ByteOutputStream
import com.twitter.finagle.http.{Method, Request, Response}
import com.twitter.finagle.service.{Backoff, RetryBudget}
import com.twitter.finagle.{Http, Service}
import com.twitter.inject.Logging
import com.twitter.io.Buf
import com.twitter.util.Duration

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

class ScalaHttpClient(implicit val ec: ExecutionContext) extends co.ledger.core.HttpClient with Logging {

  private val budget: RetryBudget = RetryBudget(
    ttl = Duration.fromSeconds(DaemonConfiguration.explorer.client.retryTtl),
    minRetriesPerSec = DaemonConfiguration.explorer.client.retryMin,
    percentCanRetry = DaemonConfiguration.explorer.client.retryPercent
  )

  private val client = Http.client
    .withRetryBudget(budget)
    .withRetryBackoff(Backoff.linear(
      Duration.fromMilliseconds(DaemonConfiguration.explorer.client.retryBackoff),
      Duration.fromMilliseconds(DaemonConfiguration.explorer.client.retryBackoff)))
    .withSessionPool.maxSize(DaemonConfiguration.explorer.client.connectionPoolSize)
    .withSessionPool.ttl(Duration.fromSeconds(DaemonConfiguration.explorer.client.connectionTtl))

  type Host = String

  // FIXME : Configure pool ttl
  // FIXME removal listener
  private val connectionPools: LoadingCache[Host, Service[Request, Response]] =
    CacheBuilder.newBuilder()
      .maximumSize(50)
      .expireAfterAccess(java.time.Duration.ofMinutes(60))
      .build[Host, Service[Request, Response]](new CacheLoader[Host, Service[Request, Response]] {
        def load(host: Host): Service[Request, Response] = {
          serviceFor(host) match {
            case Right(service) => service
            case Left(msg) => throw InvalidUrlException(msg)
          }
        }
      })

  // FIXME parse url properly
  def serviceFor(url: ScalaHttpClient.this.Host): Either[String, Service[Request, Response]] =
    url.split("/", 5).toList match {
      case _ :: _ :: uri :: _ =>
        val c = DaemonConfiguration.proxy match {
          case Some(proxy) => client.withTransport.httpProxyTo(s"${uri}:443") // FIXME TLS ??
            .withTls(uri)
            .newService(s"${proxy.host}:${proxy.port}")
          case None => client.withTls(uri).newService(s"${uri}:443")
        }
        Right(c)
      case _ => Left(s"Impossible to create connection for host $url")
    }

  import ScalaHttpClient._

  override def execute(request: HttpRequest): Unit = Future {
    val service = connectionPools.get(request.getUrl)
    val req: Request = Request(resolveMethodF(request.getMethod), request.getUrl)
    req.headerMap.put("User-Agent", "ledger-lib-core")
    req.headerMap.put("Content-Type", "application/json")
    if (request.getBody.nonEmpty) {
      req.content(Buf.ByteArray.Owned(request.getBody))
    }
    val result = for {
      response <- service(req)
    } yield {
      info(s"Received from ${request.getUrl} status=${response.status.code} error=${isOnError(response.status.code)} - statusText=${response.status.reason}")
      new ScalaHttpUrlConnection(
        response.status.code,
        response.status.reason,
        getResponseHeadersF(response),
        readResponseBodyF(response, isOnError(response.status.code)))
    }
    result.map(r => request.complete(r, null))
  }

  def execute2(request: HttpRequest): Unit = Future {
    val connection = createConnection(request)

    try {
      info(s"${request.getMethod} ${request.getUrl}")
      setRequestProperties(request, connection)

      if (request.getBody.nonEmpty) {
        connection.setDoOutput(true)
        readRequestBody(request, connection.getOutputStream)
      }

      val statusCode = connection.getResponseCode
      val statusText = connection.getResponseMessage
      val isError = isOnError(statusCode)

      info(s"Received from ${request.getUrl} status=$statusCode error=$isError - statusText=$statusText")

      val headers: util.HashMap[String, String] = getResponseHeaders(connection)
      val bodyResult: HttpReadBodyResult = readResponseBody(connection, isError)
      val proxy: HttpUrlConnection = new ScalaHttpUrlConnection(statusCode, statusText, headers, bodyResult)
      request.complete(proxy, null)
    }
    finally connection.disconnect()
  }.failed.map[co.ledger.core.Error]({
    others: Throwable =>
      new co.ledger.core.Error(ErrorCode.HTTP_ERROR, others.getMessage)
  }).foreach(request.complete(null, _))

  private def getResponseHeaders(connection: HttpURLConnection) = {
    val headers = new util.HashMap[String, String]()
    for ((key, list) <- connection.getHeaderFields.asScala) {
      headers.put(key, list.get(list.size() - 1))
    }
    headers
  }

  private def getResponseHeadersF(response: Response) = {
    val headers = new util.HashMap[String, String]()
    for ((key, value) <- response.headerMap) {
      headers.put(key, value)
    }
    headers
  }

  private def isOnError(statusCode: Int) = {
    !(statusCode >= 200 && statusCode < 400)
  }

  private def readRequestBody(request: HttpRequest, os: OutputStream) = {
    val dataOs = new DataOutputStream(os)
    try {
      dataOs.write(request.getBody)
      dataOs.flush()
    }
    finally dataOs.close()
  }

  private def setRequestProperties(request: HttpRequest, connection: HttpURLConnection) = {
    connection.setRequestMethod(resolveMethod(request.getMethod))
    for ((key, value) <- request.getHeaders.asScala) {
      connection.setRequestProperty(key, value)
    }
    connection.setRequestProperty("User-Agent", "ledger-lib-core")
    connection.setRequestProperty("Content-Type", "application/json")
  }

  private def createConnection(request: HttpRequest) = {
    DaemonConfiguration.proxy match {
      case None => new URL(request.getUrl).openConnection().asInstanceOf[HttpURLConnection]
      case Some(proxy) =>
        new URL(request.getUrl)
          .openConnection(
            new java.net.Proxy(
              java.net.Proxy.Type.HTTP,
              new InetSocketAddress(proxy.host, proxy.port)))
          .asInstanceOf[HttpURLConnection]
    }
  }

  private def readResponseBody(connection: HttpURLConnection, onError: Boolean): HttpReadBodyResult = {
    val response =
      new BufferedInputStream(if (!onError) connection.getInputStream else connection.getErrorStream)
    val buffer = new Array[Byte](PROXY_BUFFER_SIZE)
    val outputStream = new ByteOutputStream()
    try {
      var size = 0
      do {
        size = response.read(buffer)
        if (size < buffer.length) {
          outputStream.write(buffer.slice(0, size))
        } else {
          outputStream.write(buffer)
        }
      } while (size > 0)
      val data = outputStream.getBytes
      if (onError) info(s"Received ${new String(data)}")
      new HttpReadBodyResult(null, data)
    } catch {
      case t: Throwable =>
        logger.error("Failed to read response body", t)
        val error = new co.ledger.core.Error(ErrorCode.HTTP_ERROR, "An error happened during body reading.")
        new HttpReadBodyResult(error, null)
    } finally {
      outputStream.close()
      response.close()
    }
  }

  private def readResponseBodyF(resp: Response, onError: Boolean): HttpReadBodyResult = {
    val response =
      new BufferedInputStream(if (!onError) resp.getInputStream else new ByteArrayInputStream(new Array[Byte](0)))
    val buffer = new Array[Byte](PROXY_BUFFER_SIZE)
    val outputStream = new ByteOutputStream()
    try {
      var size = 0
      do {
        size = response.read(buffer)
        if (size < buffer.length) {
          outputStream.write(buffer.slice(0, size))
        } else {
          outputStream.write(buffer)
        }
      } while (size > 0)
      val data = outputStream.getBytes
      if (onError) info(s"Received ${new String(data)}")
      new HttpReadBodyResult(null, data)
    } catch {
      case t: Throwable =>
        logger.error("Failed to read response body", t)
        val error = new co.ledger.core.Error(ErrorCode.HTTP_ERROR, "An error happened during body reading.")
        new HttpReadBodyResult(error, null)
    } finally {
      outputStream.close()
      response.close()
    }
  }

  private def resolveMethod(method: HttpMethod) = method match {
    case HttpMethod.GET => "GET"
    case HttpMethod.POST => "POST"
    case HttpMethod.PUT => "PUT"
    case HttpMethod.DEL => "DELETE"
  }

  private def resolveMethodF(method: HttpMethod): Method = method match {
    case HttpMethod.GET => Method.Get
    case HttpMethod.POST => Method.Post
    case HttpMethod.PUT => Method.Put
    case HttpMethod.DEL => Method.Delete
    case _ => throw InvalidUrlException(s"Unsupported method ${method.name()}")
  }
}

object ScalaHttpClient {
  val PROXY_BUFFER_SIZE: Int = 4 * 4096
}
