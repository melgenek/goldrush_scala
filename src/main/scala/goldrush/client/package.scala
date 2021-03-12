package goldrush

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray, readFromStream, writeToArray}
import goldrush.metrics.{InFlight, RequestLatencies, elapsedSeconds}
import sttp.client3._
import sttp.model.MediaType
import zio.clock.Clock
import zio.duration._
import zio.{Has, RIO, Schedule, Task, UIO, ZIO}

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient.Version
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import scala.util.control.NoStackTrace

package object client {

  type MineClient = Has[MineClient.Service]

  type MineResponse = HttpResponse[Array[Byte]]

  implicit class HttpClientOps(val client: HttpClient) extends AnyVal {
    def sendRequest[A: JsonValueCodec](uri: URI,
                                       body: A,
                                       timeout: Duration): RIO[Clock, MineResponse] = {
      for {
        start <- UIO(System.nanoTime())
        _ = InFlight.labels(uri.getPath).inc()
        response <- ZIO
          .fromCompletionStage {
            val request = HttpRequest.newBuilder(uri)
              .headers("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofByteArray(writeToArray(body)))
//              .timeout(timeout)
              .build()
            client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
          }
          .tap { r =>
            UIO {
              InFlight.labels(uri.getPath).dec()
              RequestLatencies.labels(uri.getPath, r.statusCode.toString).observe(elapsedSeconds(start))
            }
          }
          .unrefineTo[Throwable]
          .tapError {
            case _: HttpTimeoutException =>
              UIO {
                InFlight.labels(uri.getPath).dec()
                RequestLatencies.labels(uri.getPath, "553").observe(elapsedSeconds(start))
              }
            case _ =>
              UIO {
                InFlight.labels(uri.getPath).dec()
                RequestLatencies.labels(uri.getPath, "555").observe(elapsedSeconds(start))
              }
          }
      } yield response
    }

  }

}
