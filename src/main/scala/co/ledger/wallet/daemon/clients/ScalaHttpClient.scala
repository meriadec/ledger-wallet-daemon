package co.ledger.wallet.daemon.clients

import java.io.{BufferedInputStream, ByteArrayInputStream}
import java.net.URL
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

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class ScalaHttpClient(implicit val ec: ExecutionContext) extends co.ledger.core.HttpClient with Logging {

  import ScalaHttpClient._

  override def execute(request: HttpRequest): Unit = Future {
    Try(new URL(request.getUrl)).map(urlToHost).map(host => connectionPools.get(host)) match {
      case Success(service) =>
        val req: Request = Request(resolveMethod(request.getMethod), request.getUrl)
        request.getHeaders.entrySet.forEach(hkv => req.headerMap.put(hkv.getKey, hkv.getValue))
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
            getResponseHeaders(response),
            readResponseBody(response, isOnError(response.status.code)))
        }
        result.map(r => request.complete(r, null))
      case Failure(exception) => request.complete(null, new Error(ErrorCode.HTTP_ERROR, s"Failed to parse url ${request.getUrl} => ${exception.getMessage}"))
    }
  }

  private def getResponseHeaders(response: Response) = {
    val headers = new util.HashMap[String, String]()
    for ((key, value) <- response.headerMap) {
      headers.put(key, value)
    }
    headers
  }

  private def isOnError(statusCode: Int) = {
    !(statusCode >= 200 && statusCode < 400)
  }

  private def readResponseBody(resp: Response, onError: Boolean): HttpReadBodyResult = {
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

  private def resolveMethod(method: HttpMethod): Method = method match {
    case HttpMethod.GET => Method.Get
    case HttpMethod.POST => Method.Post
    case HttpMethod.PUT => Method.Put
    case HttpMethod.DEL => Method.Delete
    case _ => throw InvalidUrlException(s"Unsupported method ${method.name()}")
  }
}

object ScalaHttpClient {
  val PROXY_BUFFER_SIZE: Int = 4 * 4096

  case class Host(hostName: String, protocol: String, port: Int)

  private[this] val budget: RetryBudget = RetryBudget(
    ttl = Duration.fromSeconds(DaemonConfiguration.explorer.client.retryTtl),
    minRetriesPerSec = DaemonConfiguration.explorer.client.retryMin,
    percentCanRetry = DaemonConfiguration.explorer.client.retryPercent
  )
  // FIXME Client sharing ?
  private val client = Http.client
    .withRetryBudget(budget)
    .withRetryBackoff(Backoff.linear(
      Duration.fromMilliseconds(DaemonConfiguration.explorer.client.retryBackoff),
      Duration.fromMilliseconds(DaemonConfiguration.explorer.client.retryBackoff)))
    .withSessionPool.maxSize(DaemonConfiguration.explorer.client.connectionPoolSize)
    .withSessionPool.ttl(Duration.fromSeconds(DaemonConfiguration.explorer.client.connectionTtl))

  // FIXME : Configure cache pool ttl and cache size
  private val connectionPools: LoadingCache[Host, Service[Request, Response]] =
    CacheBuilder.newBuilder()
      .maximumSize(50)
      .build[Host, Service[Request, Response]](new CacheLoader[Host, Service[Request, Response]] {
        def load(host: Host): Service[Request, Response] = {
          serviceFor(host) match {
            case Right(service) => service
            case Left(msg) => throw InvalidUrlException(msg)
          }
        }
      })

  def serviceFor(host: Host): Either[String, Service[Request, Response]] = {
    DaemonConfiguration.proxy match {
      case Some(proxy) => Right(tls(host, client).withTransport.httpProxyTo(s"${host.hostName}:${host.port}")
        .newService(s"${proxy.host}:${proxy.port}"))
      case None => Right(tls(host, client).newService(s"${host.hostName}:${host.port}"))
    }
  }

  def tls(host: Host, client: Http.Client): Http.Client =
    host.protocol match {
      case "https" => client.withTls(host.hostName)
      case _ => client
    }

  def resolvePort(url: URL): Int = url.getPort match {
    case port if port > 0 => port
    case _ => url.getProtocol match {
      case "https" => 443
      case _ => 80 // Port 80 by default
    }
  }

  def poolCacheSize: Long = connectionPools.size()

  def isHostCached(host: Host): Boolean = connectionPools.asMap().containsKey(host)

  def urlToHost(url: URL): Host = Host(url.getHost, url.getProtocol, resolvePort(url))
}
