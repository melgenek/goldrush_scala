package goldrush.client

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray, writeToArray}
import goldrush.Main2.IsLocal
import goldrush.metrics.{InFlight, RequestLatencies, elapsedSeconds}
import goldrush.models._
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.ratelimiter.{RateLimiter, RateLimiterConfig, RateLimiterRegistry}
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator
import io.github.resilience4j.reactor.retry.RetryOperator
import io.github.resilience4j.retry.{Retry, RetryConfig, RetryRegistry}
import reactor.core.publisher.Mono

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.time.Duration
import java.util.concurrent.{ConcurrentHashMap, TimeoutException}
import scala.collection.mutable

class FluxMineClient(host: String) {

  val config: RateLimiterConfig = RateLimiterConfig.custom
    .limitRefreshPeriod(Duration.ofMillis(200))
    .limitForPeriod(2000)
    .timeoutDuration(Duration.ofMillis(2000))
    .build()
  val rateLimiterRegistry: RateLimiterRegistry = RateLimiterRegistry.of(config)
  val exploreLimiter: RateLimiter = rateLimiterRegistry.rateLimiter("explore")
  val digLimiter: RateLimiter = rateLimiterRegistry.rateLimiter("dig")
  val cashLimiter: RateLimiter = rateLimiterRegistry.rateLimiter("cash")
  val licenseLimiter: RateLimiter = rateLimiterRegistry.rateLimiter("license")

  val customTimeout = if (IsLocal) Duration.ofMillis(5000) else Duration.ofMillis(100)

  private val exploreUri = new URI(s"http://$host:8000/explore")
  private val licenseUri = new URI(s"http://$host:8000/licenses")
  private val digUri = new URI(s"http://$host:8000/dig")
  private val cashUri = new URI(s"http://$host:8000/cash")

  private val client = HttpClient.newHttpClient()

  private def exploreJsoniter(area: Area)(r: HttpResponse[Array[Byte]]): ExploreReport =
    if (r.statusCode() == 200) readFromArray[ExploreReport](r.body())
    else ExploreReport(area, 0)

  def explore(area: Area): Mono[ExploreReport] = {
    sendRequest(exploreUri, area, customTimeout)
      .transformDeferred(RateLimiterOperator.of(exploreLimiter))
      .retry()
      .map(r => exploreJsoniter(area)(r))
  }

  private def licenseJsoniter(r: HttpResponse[Array[Byte]]): License =
    if (r.statusCode() == 200) readFromArray[License](r.body())
    else License.EmptyLicense

  def issueLicense(coin: List[Coin]): Mono[License] = {
    sendRequest(licenseUri, coin, zio.duration.Duration.Infinity)
      .transformDeferred(RateLimiterOperator.of(licenseLimiter))
      .retry()
      .map(licenseJsoniter)
  }

  private def digJsoniter(r: HttpResponse[Array[Byte]]): List[Gold] =
    if (r.statusCode() == 200) readFromArray[List[Gold]](r.body())
    else List.empty

  def dig(req: DigRequest): Mono[List[Gold]] = {
    sendRequest(digUri, req, customTimeout)
      .transformDeferred(RateLimiterOperator.of(digLimiter))
      .retry()
      .map(digJsoniter)
  }

  private def cashJsoniter(r: HttpResponse[Array[Byte]]): List[Coin] =
    if (r.statusCode() == 200) readFromArray[List[Coin]](r.body())
    else List.empty

  def cash(gold: Gold): Mono[List[Coin]] = {
    sendRequest(cashUri, gold, customTimeout)
      .transformDeferred(RateLimiterOperator.of(cashLimiter))
      .retry()
      .map(cashJsoniter)
  }

  private def sendRequest[A: JsonValueCodec](uri: URI, body: A, t: Duration): Mono[HttpResponse[Array[Byte]]] = {
    Mono.fromSupplier(() => {
      InFlight.labels(uri.getPath).inc()
      System.nanoTime()
    }).flatMap(start => {
      Mono
        .fromFuture({
          val request = HttpRequest.newBuilder(uri)
            .headers("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(writeToArray(body)))
            //            .timeout(t)
            .build()
          client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
        })
        .timeout(t)
        .doOnNext(r => {
          InFlight.labels(uri.getPath).dec()
          RequestLatencies.labels(uri.getPath, r.statusCode.toString).observe(elapsedSeconds(start))
        })
        .doOnError(e => e match {
          case _: HttpTimeoutException | _: TimeoutException =>
            InFlight.labels(uri.getPath).dec()
            RequestLatencies.labels(uri.getPath, "timeout").observe(elapsedSeconds(start))
          case _ =>
            InFlight.labels(uri.getPath).dec()
            RequestLatencies.labels(uri.getPath, "unknown").observe(elapsedSeconds(start))
        })
        .flatMap(r => if (r.statusCode() >= 500) Mono.error(ServerError(r.statusCode())) else Mono.just(r))
    })
  }

}
