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
import reactor.netty.http.client.HttpClientResponse

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

  val reactorClient = reactor.netty.http.client.HttpClient.create().host(host).port(8000)

  val customTimeout = if (IsLocal) Duration.ofMillis(20) else Duration.ofMillis(100)

  private def exploreJsoniter(area: Area)(r: ResponseAndBody): ExploreReport =
    if (r.statusCode == 200) readFromByteBuffer[ExploreReport](r.body)
    else ExploreReport(area, 0)

  def explore(area: Area): Mono[ExploreReport] = {
    sendRequest("/explore", area, customTimeout)
      .transformDeferred(RateLimiterOperator.of(exploreLimiter))
      .retry()
      .map(r => exploreJsoniter(area)(r))
  }

  private def licenseJsoniter(r: ResponseAndBody): License =
    if (r.statusCode == 200) readFromByteBuffer[License](r.body)
    else License.EmptyLicense

  def issueLicense(coin: List[Coin]): Mono[License] = {
    sendRequest("/licenses", coin, zio.duration.Duration.Infinity)
      .transformDeferred(RateLimiterOperator.of(licenseLimiter))
      .retry()
      .map(licenseJsoniter)
  }

  private def digJsoniter(r: ResponseAndBody): List[Gold] =
    if (r.statusCode == 200) readFromByteBuffer[List[Gold]](r.body)
    else List.empty

  def dig(req: DigRequest): Mono[List[Gold]] = {
    sendRequest("/dig", req, customTimeout)
      .transformDeferred(RateLimiterOperator.of(digLimiter))
      .retry()
      .map(digJsoniter)
  }

  private def cashJsoniter(r: ResponseAndBody): List[Coin] =
    if (r.statusCode == 200) readFromByteBuffer[List[Coin]](r.body)
    else List.empty

  def cash(gold: Gold): Mono[List[Coin]] = {
    sendRequest("/cash", gold, customTimeout)
      .transformDeferred(RateLimiterOperator.of(cashLimiter))
      .retry()
      .map(cashJsoniter)
  }

  final case class ResponseAndBody(r: HttpClientResponse, body: ByteBuffer) {
    val statusCode: Int = r.status().code()
  }

  private def sendRequest[A: JsonValueCodec](path: String, body: A, t: Duration): Mono[ResponseAndBody] = {
    Mono.fromSupplier(() => {
      InFlight.labels(path).inc()
      System.nanoTime()
    }).flatMap(start => {
      reactorClient
        .headers(h => h.add("Content-Type", "application/json"))
        .post()
        .uri(path)
        .send((r, out) => {
          r.responseTimeout(t)
          out.send(Mono.just(Unpooled.wrappedBuffer(writeToArray(body))))
        })
        .responseSingle((r, bytes) => bytes.asByteBuffer().map(b => ResponseAndBody(r, b)))
        .doOnNext(r => {
          InFlight.labels(path).dec()
          RequestLatencies.labels(path, r.statusCode.toString).observe(elapsedSeconds(start))
        })
        .doOnError(e => e match {
          case _: HttpTimeoutException | _: TimeoutException | _: ReadTimeoutException =>
            InFlight.labels(path).dec()
            RequestLatencies.labels(path, "timeout").observe(elapsedSeconds(start))
          case _ =>
            InFlight.labels(path).dec()
            RequestLatencies.labels(path, "unknown").observe(elapsedSeconds(start))
        })
        .flatMap(r => if (r.statusCode >= 500) Mono.error(ServerError(r.statusCode)) else Mono.just(r))
    })
  }

}
