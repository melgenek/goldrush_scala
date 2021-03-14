package goldrush.client

import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray
import goldrush.Main
import goldrush.models.Coin._
import goldrush.models.Gold._
import goldrush.models._
import nl.vroste.rezilience.Bulkhead
import zio.clock.Clock
import zio.duration._
import zio.{Has, RIO, Schedule, URIO, ZIO, ZLayer}

import java.net.URI
import java.net.http.HttpClient

object MineClient {

  trait Service {
    def explore(area: Area, timeout: Duration): RIO[Clock, ExploreReport]

    def issueLicense(coin: Seq[Coin]): RIO[Clock, License]

    def listLicenses(): RIO[Clock, Array[License]]

    def dig(req: DigRequest): RIO[Clock, Array[Gold]]

    def cash(gold: Gold): RIO[Clock, Array[Coin]]
  }

  def live(host: String) =
    ZLayer.succeed(HttpClient.newHttpClient()) >+>
      MineClient.liveClient(host)

  final val EmptyGoldArray = Array.empty[Gold]
  final val EmptyCoinArray = Array.empty[Coin]

  final val DigTimeout = if (Main.IsLocal) 4000.millis else 100.millis
  final val ExploreTimeout = if (Main.IsLocal) 4000.millis else 100.millis
  final val CashTimeout = if (Main.IsLocal) 4000.millis else 100.millis

  private def liveClient(host: String): ZLayer[Has[HttpClient], Nothing, MineClient] = {
    val exploreUri = new URI(s"http://$host:8000/explore")
    val licenseUri = new URI(s"http://$host:8000/licenses")
    val digUri = new URI(s"http://$host:8000/dig")
    val cashUri = new URI(s"http://$host:8000/cash")

    ZLayer.fromServiceManaged { client =>
      for {
        bulkheadExplore <- Bulkhead.make(2000)
        bulkheadDig <- Bulkhead.make(500)
        bulkheadLicenses <- Bulkhead.make(100)
        bulkheadCash <- Bulkhead.make(300)
      } yield new Service {
        override def explore(area: Area, timeout: Duration): RIO[Clock, ExploreReport] = {
          bulkheadExplore(client.sendRequest(exploreUri, area, timeout))
            .mapError(_.toException)
            .repeatWhile(_.statusCode() > 500)
            .retry(Schedule.forever)
            .map { r =>
              if (r.statusCode() == 200) readFromArray[ExploreReport](r.body())
              else ExploreReport(area, 0)
            }
        }

        override def issueLicense(coins: Seq[Coin]): RIO[Clock, License] = {
          bulkheadDig(client.sendRequest(licenseUri, coins, zio.duration.Duration.Infinity))
            .mapError(_.toException)
            .repeatWhile(_.statusCode() > 500)
            .retry(Schedule.forever)
            .map { r =>
              if (r.statusCode() == 200) readFromArray[License](r.body())
              else License.EmptyLicense
            }
        }

        override def listLicenses(): RIO[Clock, Array[License]] = {
          //          client.sendGetRequest(licenseUri)(jsoniter[List[License]])
          ???
        }

        override def dig(req: DigRequest): RIO[Clock, Array[Gold]] = {
          bulkheadLicenses(client.sendRequest(digUri, req, DigTimeout))
            .mapError(_.toException)
            .repeatWhile(_.statusCode() > 500)
            .retry(Schedule.forever)
            .map { r =>
              if (r.statusCode() == 200) readFromArray[Array[Gold]](r.body())
              else EmptyGoldArray
            }
        }

        override def cash(gold: Gold): RIO[Clock, Array[Coin]] = {
          bulkheadCash(client.sendRequest(cashUri, gold, CashTimeout))
            .mapError(_.toException)
            .repeatWhile(_.statusCode() > 500)
            .retry(Schedule.forever)
            .map { r =>
              if (r.statusCode() == 200) readFromArray[Array[Coin]](r.body())
              else EmptyCoinArray
            }
        }
      }
    }
  }

  def explore(area: Area, timeout: Duration): ZIO[MineClient with Clock, Nothing, ExploreReport] =
    ZIO.accessM(_.get.explore(area, timeout).retry(Schedule.forever).orDie)

  def dig(digRequest: DigRequest): URIO[MineClient with Clock, Array[Gold]] =
    ZIO.accessM(_.get.dig(digRequest).retry(Schedule.forever).orDie)

  def issueLicense(coins: Seq[Coin]): RIO[MineClient with Clock, License] =
    ZIO.accessM(_.get.issueLicense(coins).retry(Schedule.forever).orDie)

  def listLicenses(): URIO[MineClient with Clock, Array[License]] =
    ZIO.accessM(_.get.listLicenses().retry(Schedule.forever).orDie)

  def cash(gold: Gold): URIO[MineClient with Clock, Array[Coin]] =
    ZIO.accessM(_.get.cash(gold).retry(Schedule.forever).orDie)

}
