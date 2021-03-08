package goldrush.client

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray, writeToArray}
import goldrush.Main2.IsLocal
import goldrush.metrics.{InFlight, RequestLatencies, elapsedSeconds}
import goldrush.models._

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.time.Duration
import scala.annotation.tailrec
import scala.concurrent.TimeoutException

class BlockingMineClient(host: String) {

  private val exploreUri = new URI(s"http://$host:8000/explore")
  private val licenseUri = new URI(s"http://$host:8000/licenses")
  private val digUri = new URI(s"http://$host:8000/dig")
  private val cashUri = new URI(s"http://$host:8000/cash")

  private val client = HttpClient.newHttpClient()

  private def exploreJsoniter(r: HttpResponse[Array[Byte]]): ExploreReport =
    if (r.statusCode() == 200) readFromArray[ExploreReport](r.body())
    else throw UnexpectedErrorCode

  def explore(area: Area): ExploreReport = {
    sendRequest(exploreUri, area)(exploreJsoniter)
  }

  private def licenseJsoniter(r: HttpResponse[Array[Byte]]): License =
    if (r.statusCode() == 200) readFromArray[License](r.body())
    else throw UnexpectedErrorCode

  def issueLicense(coin: Option[Coin]): License = {
    sendRequest(licenseUri, coin.toList)(licenseJsoniter)
  }

  private def digJsoniter(r: HttpResponse[Array[Byte]]): List[Gold] =
    if (r.statusCode() == 200) readFromArray[List[Gold]](r.body())
    else if (r.statusCode() == 404) List.empty
    else if (r.statusCode() == 422) List.empty
    else if (r.statusCode() == 403) List.empty
    else throw UnexpectedErrorCode

  def dig(req: DigRequest): List[Gold] = {
    sendRequest(digUri, req)(digJsoniter)
  }

  private def cashJsoniter(r: HttpResponse[Array[Byte]]): List[Coin] =
    if (r.statusCode() == 200) readFromArray[List[Coin]](r.body())
    else if (r.statusCode() == 409) List.empty
    else if (r.statusCode() == 404) List.empty
    else throw UnexpectedErrorCode

  def cash(gold: Gold): List[Coin] = {
    sendRequest(cashUri, gold)(cashJsoniter)
  }

  type Decode[B] = HttpResponse[Array[Byte]] => B

  @tailrec
  private def sendRequest[A: JsonValueCodec, B: JsonValueCodec](uri: URI, body: A)(decode: Decode[B]): B = {
    InFlight.labels(uri.getPath).inc()
    val start = System.nanoTime()
    try {
      val request = HttpRequest.newBuilder(uri)
        .headers("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofByteArray(writeToArray(body)))
        .timeout(if(IsLocal) Duration.ofMillis(1000) else Duration.ofMillis(100))
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
      InFlight.labels(uri.getPath).dec()
      RequestLatencies.labels(uri.getPath, response.statusCode.toString).observe(elapsedSeconds(start))
      decode(response)
    } catch {
      case _: HttpTimeoutException =>
        InFlight.labels(uri.getPath).dec()
        RequestLatencies.labels(uri.getPath, "553").observe(elapsedSeconds(start))
        sendRequest(uri, body)(decode)
      case _: Throwable =>
        InFlight.labels(uri.getPath).dec()
        RequestLatencies.labels(uri.getPath, "555").observe(elapsedSeconds(start))
        sendRequest(uri, body)(decode)
    }
  }

}
