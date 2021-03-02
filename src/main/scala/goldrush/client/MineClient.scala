package goldrush.client

import goldrush.models._
import sttp.client3._
import sttp.client3.httpclient.zio.{HttpClientZioBackend, SttpClient}
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
    HttpClientZioBackend.layer().orDie.map { c =>
      val zioBackend = c.get[SttpClient.Service]
      Has(MonitoringBackend.wrap(zioBackend))
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
