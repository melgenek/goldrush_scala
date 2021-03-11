package goldrush.client

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromByteBuffer, writeToArray}
import goldrush.Main2.IsLocal
import goldrush.metrics.{InFlight, RequestLatencies, elapsedSeconds}
import goldrush.models._
import io.github.resilience4j.ratelimiter.{RateLimiter, RateLimiterConfig, RateLimiterRegistry}
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator
import io.netty.buffer.Unpooled
import io.netty.handler.timeout.ReadTimeoutException
import reactor.core.publisher.Mono
import reactor.netty.http.HttpProtocol
import reactor.netty.http.client.HttpClientResponse

import java.net.URI
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.TimeoutException

class TrueFluxMineClient(host: String) {

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

  val reactorClient = reactor.netty.http.client.HttpClient.create()
  reactorClient.warmup().block()

  val customTimeout = if (IsLocal) Duration.ofMillis(20) else Duration.ofMillis(100)

  private val exploreUri = new URI(s"http://$host:8000/explore")
  private val licenseUri = new URI(s"http://$host:8000/licenses")
  private val digUri = new URI(s"http://$host:8000/dig")
  private val cashUri = new URI(s"http://$host:8000/cash")
  private val healthUri = new URI(s"http://$host:8000/health-check")

  def healthCheck: Mono[Unit] = {
    reactorClient
      .get()
      .uri(healthUri)
      .response()
      .map(_ => ())
      .retry()
  }

  private def exploreJsoniter(area: Area)(r: ResponseAndBody): ExploreReport =
    if (r.statusCode == 200) readFromByteBuffer[ExploreReport](r.body)
    else ExploreReport(area, 0)

  def explore(area: Area): Mono[ExploreReport] = {
    sendRequest(exploreUri, area, customTimeout)
      .transformDeferred(RateLimiterOperator.of(exploreLimiter))
      .retry()
      .map(r => exploreJsoniter(area)(r))
  }

  private def licenseJsoniter(r: ResponseAndBody): License =
    if (r.statusCode == 200) readFromByteBuffer[License](r.body)
    else License.EmptyLicense

  def issueLicense(coin: List[Coin]): Mono[License] = {
    sendRequest(licenseUri, coin, zio.duration.Duration.Infinity)
      .transformDeferred(RateLimiterOperator.of(licenseLimiter))
      .retry()
      .map(licenseJsoniter)
  }

  private def digJsoniter(r: ResponseAndBody): List[Gold] =
    if (r.statusCode == 200) readFromByteBuffer[List[Gold]](r.body)
    else List.empty

  def dig(req: DigRequest): Mono[List[Gold]] = {
    sendRequest(digUri, req, customTimeout)
      .transformDeferred(RateLimiterOperator.of(digLimiter))
      .retry()
      .map(digJsoniter)
  }

  private def cashJsoniter(r: ResponseAndBody): List[Coin] =
    if (r.statusCode == 200) readFromByteBuffer[List[Coin]](r.body)
    else List.empty

  def cash(gold: Gold): Mono[List[Coin]] = {
    sendRequest(cashUri, gold, customTimeout)
      .transformDeferred(RateLimiterOperator.of(cashLimiter))
      .retry()
      .map(cashJsoniter)
  }

  final case class ResponseAndBody(r: HttpClientResponse, body: ByteBuffer) {
    val statusCode: Int = r.status().code()
  }

  private def sendRequest[A: JsonValueCodec](uri: URI, body: A, t: Duration): Mono[ResponseAndBody] = {
    Mono.fromSupplier(() => {
      InFlight.labels(uri.getPath).inc()
      System.nanoTime()
    }).flatMap(start => {
      reactorClient
        .headers(h => h.add("Content-Type", "application/json"))
        .post()
        .uri(uri)
        .send((r, out) => {
          //          r.responseTimeout(t)
          out.send(Mono.just(Unpooled.wrappedBuffer(writeToArray(body))))
        })
        .responseSingle((r, bytes) => bytes.asByteBuffer().map(b => ResponseAndBody(r, b)))
        .doOnNext(r => {
          InFlight.labels(uri.getPath).dec()
          RequestLatencies.labels(uri.getPath, r.statusCode.toString).observe(elapsedSeconds(start))
        })
        .doOnError(e => e match {
          case _: HttpTimeoutException | _: TimeoutException | _: ReadTimeoutException =>
            InFlight.labels(uri.getPath).dec()
            RequestLatencies.labels(uri.getPath, "timeout").observe(elapsedSeconds(start))
          case _ =>
            InFlight.labels(uri.getPath).dec()
            RequestLatencies.labels(uri.getPath, "unknown").observe(elapsedSeconds(start))
        })
        .flatMap(r => if (r.statusCode >= 500) Mono.error(ServerError(r.statusCode)) else Mono.just(r))
    })
  }

}
