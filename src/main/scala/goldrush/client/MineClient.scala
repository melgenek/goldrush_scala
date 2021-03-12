package goldrush.client

import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray
import goldrush.Main
import goldrush.models._
import nl.vroste.rezilience.Bulkhead
import zio.clock.Clock
import zio.duration._
import zio.{RIO, Schedule, URIO, ZIO, ZLayer}

import java.net.URI
import java.net.http.HttpClient

object MineClient {

  trait Service {
    def explore(area: Area, timeout: Duration): RIO[Clock, ExploreReport]

    def issueLicense(coin: List[Coin]): RIO[Clock, License]

    def listLicenses(): RIO[Clock, List[License]]

    def dig(req: DigRequest): RIO[Clock, List[Gold]]

    def cash(gold: Gold): RIO[Clock, List[Coin]]
  }

  def live(host: String) =
    Bulkhead.make(1000, 100).toLayer >+>
      ZLayer.succeed(HttpClient.newHttpClient()) >+>
      MineClient.liveClient(host)

  final val EmptyGoldList = List.empty[Gold]

  final val DigTimeout = if (Main.IsLocal) 4000.millis else 100.millis
  final val ExploreTimeout = if (Main.IsLocal) 4000.millis else 100.millis
  final val CashTimeout = if (Main.IsLocal) 4000.millis else 100.millis

  private def liveClient(host: String) = {
    val exploreUri = new URI(s"http://$host:8000/explore")
    val licenseUri = new URI(s"http://$host:8000/licenses")
    val digUri = new URI(s"http://$host:8000/dig")
    val cashUri = new URI(s"http://$host:8000/cash")

    ZLayer.fromServices[HttpClient, Bulkhead, Service] { (client, bulkhead) =>
      new Service {
        override def explore(area: Area, timeout: Duration): RIO[Clock, ExploreReport] = {
          bulkhead(client.sendRequest(exploreUri, area, timeout))
            .mapError(_.toException)
            .repeatWhile(_.statusCode() > 500)
            .retry(Schedule.forever)
            .map { r =>
              if (r.statusCode() == 200) readFromArray[ExploreReport](r.body())
              else ExploreReport(area, 0)
            }
        }

        override def issueLicense(coins: List[Coin]): RIO[Clock, License] = {
          bulkhead(client.sendRequest(licenseUri, coins, zio.duration.Duration.Infinity))
            .mapError(_.toException)
            .repeatWhile(_.statusCode() > 500)
            .retry(Schedule.forever)
            .map { r =>
              if (r.statusCode() == 200) readFromArray[License](r.body())
              else License.EmptyLicense
            }
        }

        override def listLicenses(): RIO[Clock, List[License]] = {
          //          client.sendGetRequest(licenseUri)(jsoniter[List[License]])
          ???
        }

        override def dig(req: DigRequest): RIO[Clock, List[Gold]] = {
          bulkhead(client.sendRequest(digUri, req, DigTimeout))
            .mapError(_.toException)
            .repeatWhile(_.statusCode() > 500)
            .retry(Schedule.forever)
            .map { r =>
              if (r.statusCode() == 200) readFromArray[List[Gold]](r.body())
              else List.empty
            }
        }

        override def cash(gold: Gold): RIO[Clock, List[Coin]] = {
          bulkhead(client.sendRequest(cashUri, gold, CashTimeout))
            .mapError(_.toException)
            .repeatWhile(_.statusCode() > 500)
            .retry(Schedule.forever)
            .map { r =>
              if (r.statusCode() == 200) readFromArray[List[Coin]](r.body())
              else List.empty
            }
        }
      }
    }
  }

  def explore(area: Area, timeout: Duration): ZIO[MineClient with Clock, Nothing, ExploreReport] =
    ZIO.accessM(_.get.explore(area, timeout).retry(Schedule.forever).orDie)

  def dig(digRequest: DigRequest): URIO[MineClient with Clock, List[Gold]] =
    ZIO.accessM(_.get.dig(digRequest).retry(Schedule.forever).orDie)

  def issueLicense(coins: List[Coin]): RIO[MineClient with Clock, License] =
    ZIO.accessM(_.get.issueLicense(coins).retry(Schedule.forever).orDie)

  def listLicenses(): URIO[MineClient with Clock, List[License]] =
    ZIO.accessM(_.get.listLicenses().retry(Schedule.forever).orDie)

  def cash(gold: Gold): URIO[MineClient with Clock, List[Coin]] =
    ZIO.accessM(_.get.cash(gold).retry(Schedule.forever).orDie)

}
