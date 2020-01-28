package co.ledger.wallet.daemon.clients

import co.ledger.wallet.daemon.configurations.DaemonConfiguration
import co.ledger.wallet.daemon.models.FeeMethod
import co.ledger.wallet.daemon.utils.HexUtils
import co.ledger.wallet.daemon.utils.Utils._
import com.fasterxml.jackson.annotation.JsonProperty
import com.twitter.finagle.http.{Method, Request, Response}
import com.twitter.finagle.service.{Backoff, RetryBudget}
import com.twitter.finagle.{Http, Service}
import com.twitter.finatra.json.FinatraObjectMapper
import com.twitter.inject.Logging
import com.twitter.util.Duration
import io.circe.Json
import javax.inject.Singleton

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.collection.JavaConverters._

// TODO: Map response from service to be more readable
@Singleton
class ApiClient(implicit val ec: ExecutionContext) extends Logging {

  import ApiClient._

  private val mapper: FinatraObjectMapper = FinatraObjectMapper.create()

  def getFees(currencyName: String): Future[FeeInfo] = {
    val path = getPathForCurrency(currencyName)
    val (host, service) = services.getOrElse(currencyName, services("default"))
    val request = Request(Method.Get, path).host(host)
    service(request).map { response =>
      mapper.objectMapper.readTree(response.contentString)
        .fields.asScala.filter(_.getKey forall Character.isDigit)
        .map(_.getValue.asInt).toList.sorted.map(BigInt.apply) match {
        case low::medium::high::Nil => FeeInfo(high, medium, low)
        case _ =>
          warn(s"Failed to retrieve fees from explorer, falling back on default fees.")
          defaultBTCFeeInfo
      }
    }.asScala()
  }

  def getFeesRipple: Future[BigInt] = {
    val (host, service) = services.getOrElse("ripple", services("default"))
    val request = Request(Method.Post, "/").host(host)
    val body = "{\"method\":\"server_state\",\"params\":[{}]}"

    request.setContentString(body)
    request.setContentType("application/json")

    service(request).map { response =>
      import io.circe.parser.parse
      val json = parse(response.contentString)
      val result = json.flatMap { j =>
        for {
          rippleResult <- j.hcursor.get[Json]("result")
          rippleState <- rippleResult.hcursor.get[Json]("state")
          rippleValidatedLedger <- rippleState.hcursor.get[Json]("validated_ledger")
          baseFee <- rippleValidatedLedger.hcursor.get[Double]("base_fee")
          loadFactor <- rippleState.hcursor.get[Double]("load_factor")
          loadBase <- rippleState.hcursor.get[Double]("load_base")
        } yield {
          info(s"Query rippled server_state: baseFee=${baseFee} loadFactor:${loadFactor} loadBase=${loadBase}")
          BigInt(((baseFee * loadFactor) / loadBase).toInt)
        }
      }

      result.getOrElse {
        info(s"Failed to query server_state method of ripple daemon: " +
          s"uri=${host} request=${request.contentString} response=${response.contentString}")
        defaultXRPFees
      }

    }
    }.asScala()

  def getGasLimit(currencyName: String, recipient: String, source: Option[String] = None, inputData: Option[Array[Byte]] = None): Future[BigInt] = {
    import io.circe.syntax._
    val (host, service) = services.getOrElse(currencyName, services("default"))

    val uri = s"/blockchain/v3/addresses/${recipient.toLowerCase}/estimate-gas-limit"
    val request = Request(
      Method.Post,
      uri
    ).host(host)
    val body = source.map(s => Map[String, String]("from" -> s)).getOrElse(Map[String, String]()) ++
      inputData.map(d => Map[String, String]("data" -> s"0x${HexUtils.valueOf(d)}")).getOrElse(Map[String, String]())
    request.setContentString(body.asJson.noSpaces)
    request.setContentType("application/json")

    service(request).map { response =>
      Try(mapper.parse[GasLimit](response).limit).fold(
        _ => {
          info(s"Failed to estimate gas limit, using default: Request=${request.contentString} ; Response=${response.contentString}")
          defaultGasLimit
        },
        result => {
          info(s"getGasLimit uri=${host}${uri} request=${request.contentString} response:${response.contentString}")
          result
        }
      )
    }.asScala()
  }

  def getGasPrice(currencyName: String): Future[BigInt] = {
    val (host, service) = services.getOrElse(currencyName, services("default"))
    val path = getPathForCurrency(currencyName)
    val request = Request(Method.Get, path).host(host)

    service(request).map { response =>
      mapper.parse[GasPrice](response).price
    }.asScala()
  }

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

  private val services: Map[String, (String, Service[Request, Response])] =
    DaemonConfiguration.explorer.api.paths
      .map { case (currency, path) =>
        val p = path.filterPrefix
        currency -> (s"${p.host}:${p.port}", {
          DaemonConfiguration.proxy match {
            case Some(proxy) => client.withTransport.httpProxyTo(s"${p.host}:${p.port}").withTls(proxy.host).newService(s"${proxy.host}:${proxy.port}")
            case None => client.withTls(p.host).newService(s"${p.host}:${p.port}")
          }
        })
      }
  lazy private val fallbackClientServices: Map[String, (FallbackParams, Service[Request, Response])] = {
    DaemonConfiguration.explorer.api.paths
      .mapValues { config =>
        for {
          f <- config.filterPrefix.fallback
          r <- f.split("/", 2).toList match {
            case host :: query :: _ =>
              val c = DaemonConfiguration.proxy match {
                case Some(proxy) => client.withTransport.httpProxyTo(s"${host}:443")
                  .withTls(host)
                  .newService(s"${proxy.host}:${proxy.port}")
                case None => client.withTls(host).newService(s"${host}:443")
              }
              Some(FallbackParams(s"${host}:443", "/" + query), c)
            case _ =>
              None
          }
        } yield r
      }.collect {
      case (currency, opt) if opt.isDefined => (currency, opt.get)
    }
  }

  def fallbackClient(currency: String): Option[(FallbackParams, Service[Request, Response])] = {
    fallbackClientServices.get(currency)
  }

  private def getPathForCurrency(currencyName: String): String = {
  val path = mappedPaths.getOrElse(currencyName, throw new UnsupportedOperationException(s"Currency not supported '$currencyName'"))
    DaemonConfiguration.explorer.api.paths.get(currencyName).flatMap(_.explorerVersion) match {
      case Some(version) => path.replaceFirst("/v[0-9]+/", s"/$version/")
      case _ => path
    }
  }

  private val mappedPaths: Map[String, String] = {
    Map(
      "bitcoin" -> "/blockchain/v2/btc/fees",
      "bitcoin_testnet" -> "/blockchain/v2/btc_testnet/fees",
      "dogecoin" -> "/blockchain/v2/doge/fees",
      "litecoin" -> "/blockchain/v2/ltc/fees",
      "dash" -> "/blockchain/v2/dash/fees",
      "komodo" -> "/blockchain/v2/kmd/fees",
      "pivx" -> "/blockchain/v2/pivx/fees",
      "viacoin" -> "/blockchain/v2/via/fees",
      "vertcoin" -> "/blockchain/v2/vtc/fees",
      "digibyte" -> "/blockchain/v2/dgb/fees",
      "bitcoin_cash" -> "/blockchain/v2/abc/fees",
      "poswallet" -> "/blockchain/v2/posw/fees",
      "stratis" -> "/blockchain/v2/strat/fees",
      "peercoin" -> "/blockchain/v2/ppc/fees",
      "bitcoin_gold" -> "/blockchain/v2/btg/fees",
      "zcash" -> "/blockchain/v2/zec/fees",
      "ethereum" -> "/blockchain/v3/fees",
      "ethereum_classic" -> "/blockchain/v3/fees",
      "ethereum_ropsten" -> "/blockchain/v3/fees"
    )
  }
  private val defaultGasLimit = BigInt(200000)
  private val defaultXRPFees = BigInt(10)

  // {"2":18281,"3":12241,"6":10709,"last_updated":1580478904}
  private val defaultBTCFeeInfo = FeeInfo(18281, 12241, 10709)
}

object ApiClient {

  case class FallbackParams(host: String, query: String)

  case class FeeInfo(fast: BigInt, normal: BigInt, slow: BigInt) {
    def getAmount(feeMethod: FeeMethod): BigInt = feeMethod match {
      case FeeMethod.FAST => fast / 1000
      case FeeMethod.NORMAL => normal / 1000
      case FeeMethod.SLOW => slow / 1000
    }
  }

  case class GasPrice(@JsonProperty("gas_price") price: BigInt)

  case class GasLimit(@JsonProperty("estimated_gas_limit") limit: BigInt)

}
