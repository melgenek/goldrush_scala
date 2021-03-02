package goldrush

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray, writeToArray}
import goldrush.client.MonitoringBackend.path
import goldrush.metrics.{InFlight, RequestLatencies, elapsedSeconds}
import sttp.client3._
import sttp.model.MediaType
import zio.{Has, Task, UIO, ZIO}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.util.control.NoStackTrace

package object client {

  type MineClient = Has[MineClient.Service]

  def asJsoniterAlways[A: JsonValueCodec]: ResponseAs[A, Any] = asJsoniter.getRight

  def asJsoniter[A: JsonValueCodec]: ResponseAs[Either[String, A], Any] = asByteArray.mapRight(readFromArray(_))

  implicit def jsoniterBodySerializer[A: JsonValueCodec]: BodySerializer[A] =
    b => ByteArrayBody(writeToArray(b), MediaType.ApplicationJson)

  final object UnexpectedErrorCode extends NoStackTrace

  type DecodeResponse[B] = HttpResponse[Array[Byte]] => Either[Throwable, B]

  def jsoniter[B: JsonValueCodec](r: HttpResponse[Array[Byte]]): Either[Throwable, B] =
    if (r.statusCode() == 200) Right(readFromArray(r.body()))
    else Left(UnexpectedErrorCode)

  implicit class HttpClientOps(val client: HttpClient) extends AnyVal {
    def sendRequest[A: JsonValueCodec, B: JsonValueCodec](uri: URI, body: A)(decode: DecodeResponse[B]): Task[B] = {
      for {
        start <- UIO(System.nanoTime())
        _ = InFlight.labels(uri.getPath).inc()
        response <- ZIO
          .fromCompletionStage {
            val request = HttpRequest.newBuilder(uri)
              .headers("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofByteArray(writeToArray(body)))
              .build()
            client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
          }
          .tap { r =>
            UIO {
              InFlight.labels(uri.getPath).dec()
              RequestLatencies.labels(uri.getPath, r.statusCode.toString).observe(elapsedSeconds(start))
            }
          }
          .tapError { _ =>
            UIO {
              InFlight.labels(uri.getPath).dec()
              RequestLatencies.labels(uri.getPath, "555").observe(elapsedSeconds(start))
            }
          }
          .flatMap(r => ZIO.fromEither(decode(r)))
      } yield response
    }
  }

}
