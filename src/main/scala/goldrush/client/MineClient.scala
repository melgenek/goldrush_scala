package goldrush.client

import goldrush.models._
import org.asynchttpclient.{AsyncHttpClient, AsyncHttpClientConfig, DefaultAsyncHttpClient, DefaultAsyncHttpClientConfig}
import sttp.client3._
import sttp.client3.asynchttpclient.zio.{AsyncHttpClientZioBackend, SttpClient}
import sttp.client3.prometheus._
import sttp.model.StatusCode
import zio.clock.Clock
import zio.{Has, Schedule, Task, URIO, ZIO, ZLayer}

object MineClient {

  trait Service {
    def explore(area: Area): Task[ExploreReport]

    def issueLicense(coin: Option[Coin]): Task[License]

    def dig(req: DigRequest): Task[List[Gold]]

    def cash(gold: Gold): Task[List[Coin]]
  }

  final val Buckets = List(.1, 1)

  def live(host: String) =
    AsyncHttpClientZioBackend.layer().orDie.map { c =>
      val zioBackend = c.get[SttpClient.Service]
      Has(PrometheusBackend(
        zioBackend,
        requestToHistogramNameMapper = req => Some(HistogramCollectorConfig("request_latency", List("path" -> req.uri.path.mkString("/")), Buckets)),
        requestToInProgressGaugeNameMapper = req => Some(CollectorConfig("in_progress", List("path" -> req.uri.path.mkString("/")))),
        requestToSuccessCounterMapper = req => Some(CollectorConfig("success", List("path" -> req.uri.path.mkString("/")))),
        requestToErrorCounterMapper = req => Some(CollectorConfig("error", List("path" -> req.uri.path.mkString("/")))),
        requestToFailureCounterMapper = req => Some(CollectorConfig("failure", List("path" -> req.uri.path.mkString("/"))))
      ))
    } >+> MineClient.liveClient(host)

  private def liveClient(host: String) =
    ZLayer.fromService[SttpClient.Service, Service] { backend =>

      new Service {
        override def explore(area: Area): Task[ExploreReport] = {
          basicRequest
            .post(uri"http://$host:8000/explore")
            .body(area)
            .response(asJsoniterAlways[ExploreReport])
            .send(backend)
            .map(_.body)
            .unrefineTo[Throwable]
        }

        override def issueLicense(coin: Option[Coin]): Task[License] = {
          basicRequest
            .post(uri"http://$host:8000/licenses")
            .body(coin.toList)
            .response(asJsoniterAlways[License])
            .send(backend)
            .map(_.body)
            .unrefineTo[Throwable]
        }

        override def dig(req: DigRequest): Task[List[Gold]] = {
          basicRequest
            .post(uri"http://$host:8000/dig")
            .body(req)
            .response(fromMetadata(
              asStringAlways.map(Left(_)),
              ConditionalResponseAs(_.code == StatusCode.Ok, asJsoniterAlways[List[Gold]].map(Right(_))),
              ConditionalResponseAs(_.code == StatusCode.NotFound, IgnoreResponse.map(_ => Right(List.empty[Gold])))
            ).getRight)
            .send(backend)
            .map(_.body)
            .unrefineTo[Throwable]
        }

        def cash(gold: Gold): Task[List[Coin]] = {
          basicRequest
            .post(uri"http://$host:8000/cash")
            .body(gold)
            .response(asJsoniterAlways[List[Coin]])
            .send(backend)
            .map(_.body)
            .unrefineTo[Throwable]
        }
      }
    }


  def explore(area: Area): ZIO[MineClient with Clock, Nothing, ExploreReport] =
    ZIO.accessM(_.get.explore(area).retry(Schedule.forever).orDie)


  def dig(digRequest: DigRequest): URIO[MineClient with Clock, List[Gold]] =
    ZIO.accessM(_.get.dig(digRequest).retry(Schedule.forever).orDie)

  def issueLicense(coin: Option[Coin]): URIO[MineClient with Clock, License] =
    ZIO.accessM(_.get.issueLicense(coin).retry(Schedule.forever).orDie)

  def cash(gold: Gold): URIO[MineClient with Clock, List[Coin]] =
    ZIO.accessM(_.get.cash(gold).retry(Schedule.forever).orDie)


}
