package goldrush.client

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray, writeToArray}
import goldrush.Main2.IsLocal
import goldrush.metrics.{InFlight, RequestLatencies, elapsedSeconds}
import goldrush.models._
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.ratelimiter.{RateLimiter, RateLimiterConfig, RateLimiterRegistry}
import io.github.resilience4j.retry.{Retry, RetryConfig, RetryRegistry}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.time.Duration
import scala.annotation.tailrec

class BlockingMineClient(host: String) {

  private val exploreUri = new URI(s"http://$host:8000/explore")
  private val licenseUri = new URI(s"http://$host:8000/licenses")
  private val digUri = new URI(s"http://$host:8000/dig")
  private val cashUri = new URI(s"http://$host:8000/cash")
  private val healthCheckUri = new URI(s"http://$host:8000/health-check")

  private val client = HttpClient.newHttpClient()

  val retryConfig: RetryConfig = RetryConfig.custom
    .maxAttempts(Int.MaxValue)
    .intervalFunction(IntervalFunction.ofRandomized(30))
    .retryOnResult((response: HttpResponse[Array[Byte]]) => response.statusCode() >= 500)
    .retryOnException(e => {
      true
    })
    .build()
  val retry: Retry = RetryRegistry.of(retryConfig).retry("MineClient")

  val config: RateLimiterConfig = RateLimiterConfig.custom
    .limitRefreshPeriod(Duration.ofMillis(200))
    .limitForPeriod(200)
    .timeoutDuration(Duration.ofMillis(2000))
    .build()
  val rateLimiterRegistry: RateLimiterRegistry = RateLimiterRegistry.of(config)
  val exploreLimiter: RateLimiter = rateLimiterRegistry.rateLimiter("explore")
  val digLimiter: RateLimiter = rateLimiterRegistry.rateLimiter("dig")
  val cashLimiter: RateLimiter = rateLimiterRegistry.rateLimiter("cash")
  val licenseLimiter: RateLimiter = rateLimiterRegistry.rateLimiter("license")

  val customTimeout = if (IsLocal) Duration.ofMillis(5000) else Duration.ofMillis(100)

  @tailrec
  final def healthCheck(): Unit = {
    try {
      val request = HttpRequest.newBuilder(healthCheckUri)
        .headers("Content-Type", "application/json")
        .GET()
        .timeout(if (IsLocal) Duration.ofMillis(1000) else Duration.ofMillis(400))
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.discarding())
      if (response.statusCode() != 200) throw UnexpectedErrorCode
      else ()
    } catch {
      case _: Throwable => healthCheck()
    }
  }

  private def exploreJsoniter(r: HttpResponse[Array[Byte]]): ExploreReport =
    if (r.statusCode() == 200) readFromArray[ExploreReport](r.body())
    else throw UnexpectedErrorCode

  //  def explore(area: Area) = {
  //    sendRequest(exploreUri, area, customTimeout, exploreLimiter)(exploreJsoniter)
  //  }

  val explore = Retry.decorateCheckedFunction(retry,
    RateLimiter.decorateCheckedFunction(exploreLimiter, (area: Area) => {
      sendRawRequest(exploreUri, area, customTimeout)
    })
  ).andThen(exploreJsoniter)

  private def licenseJsoniter(r: HttpResponse[Array[Byte]]): License =
    if (r.statusCode() == 200) readFromArray[License](r.body())
    else if (r.statusCode() == 409) License.EmptyLicense
    else throw UnexpectedErrorCode

  //  def issueLicense(coin: Option[Coin]) = {
  //    sendRequest(licenseUri, coin.toList, zio.duration.Duration.Infinity, licenseLimiter)(licenseJsoniter)
  //  }


  val issueLicense =
    Retry.decorateCheckedFunction(retry,
      RateLimiter.decorateCheckedFunction(licenseLimiter, (coin: Option[Coin]) => {
        sendRawRequest(licenseUri, coin.toList, zio.duration.Duration.Infinity)
      })
    ).andThen(licenseJsoniter)

  private def digJsoniter(r: HttpResponse[Array[Byte]]): List[Gold] =
    if (r.statusCode() == 200) readFromArray[List[Gold]](r.body())
    else if (r.statusCode() == 404) List.empty
    else if (r.statusCode() == 422) List.empty
    else if (r.statusCode() == 403) List.empty
    else throw UnexpectedErrorCode

  //  def dig(req: DigRequest) = {
  //    sendRequest(digUri, req, customTimeout, digLimiter)(digJsoniter)
  //  }

  val dig = Retry.decorateCheckedFunction(retry,
    RateLimiter.decorateCheckedFunction(digLimiter, (req: DigRequest) => {
      sendRawRequest(digUri, req, customTimeout)
    })
  ).andThen(digJsoniter)


  private def cashJsoniter(r: HttpResponse[Array[Byte]]): List[Coin] =
    if (r.statusCode() == 200) readFromArray[List[Coin]](r.body())
    else if (r.statusCode() == 409) List.empty
    else if (r.statusCode() == 404) List.empty
    else throw UnexpectedErrorCode

  //  def cash(gold: Gold) = {
  //    sendRequest(cashUri, gold, customTimeout, cashLimiter)(cashJsoniter)
  //  }

  val cash = Retry.decorateCheckedFunction(retry,
    RateLimiter.decorateCheckedFunction(cashLimiter, (gold: Gold) => {
      sendRawRequest(cashUri, gold, customTimeout)
    })
  ).andThen(cashJsoniter)

  type Decode[B] = HttpResponse[Array[Byte]] => B


  private def sendRawRequest[A: JsonValueCodec](uri: URI, body: A, t: Duration): HttpResponse[Array[Byte]] = {
    InFlight.labels(uri.getPath).inc()
    val start = System.nanoTime()
    try {
      val request = HttpRequest.newBuilder(uri)
        .headers("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofByteArray(writeToArray(body)))
        .timeout(t)
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
      RequestLatencies.labels(uri.getPath, response.statusCode.toString).observe(elapsedSeconds(start))
      response
    } catch {
      case e: HttpTimeoutException =>
        RequestLatencies.labels(uri.getPath, "553").observe(elapsedSeconds(start))
        throw e
      case e: Throwable =>
        RequestLatencies.labels(uri.getPath, "555").observe(elapsedSeconds(start))
        throw e
    } finally {
      InFlight.labels(uri.getPath).dec()
    }
  }

  @tailrec
  private def sendRequest[A: JsonValueCodec, B: JsonValueCodec](uri: URI, body: A, t: Duration, rateLimiter: RateLimiter)
                                                               (decode: Decode[B]): B = {
    InFlight.labels(uri.getPath).inc()
    val start = System.nanoTime()
    try {
      val request = HttpRequest.newBuilder(uri)
        .headers("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofByteArray(writeToArray(body)))
        .timeout(t)
        .build()
      val response = rateLimiter.executeCheckedSupplier(() => client.send(request, HttpResponse.BodyHandlers.ofByteArray()))
      InFlight.labels(uri.getPath).dec()
      RequestLatencies.labels(uri.getPath, response.statusCode.toString).observe(elapsedSeconds(start))
      decode(response)
    } catch {
      //      case _: Unexpected =>
      //        sendRequest(uri, body, t, rateLimiter)(decode)
      case e: HttpTimeoutException =>
        InFlight.labels(uri.getPath).dec()
        RequestLatencies.labels(uri.getPath, "553").observe(elapsedSeconds(start))
        sendRequest(uri, body, t, rateLimiter)(decode)
      case _: Throwable =>
        InFlight.labels(uri.getPath).dec()
        RequestLatencies.labels(uri.getPath, "555").observe(elapsedSeconds(start))
        sendRequest(uri, body, t, rateLimiter)(decode)
    }
  }

}
