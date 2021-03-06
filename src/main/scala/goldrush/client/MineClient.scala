package goldrush.client

import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray
import goldrush.models._
import zio.clock.Clock
import zio.{RIO, Schedule, Task, URIO, ZIO, ZLayer}
import zio.duration._

import java.net.URI
import java.net.http.{HttpClient, HttpResponse}

object MineClient {

  trait Service {
    def explore(area: Area): Task[ExploreReport]

    def issueLicense(coin: List[Coin]): Task[License]

    def listLicenses(): Task[List[License]]

    def dig(req: DigRequest): Task[List[Gold]]

    def cash(gold: Gold): Task[List[Coin]]
  }

  def live(host: String) =
    ZLayer.succeed(HttpClient.newHttpClient()) >+> MineClient.liveClient(host)

  final val EmptyGoldList = List.empty[Gold]

  def jsoniterDig(r: HttpResponse[Array[Byte]]): Either[Throwable, List[Gold]] =
    if (r.statusCode() == 200) Right(readFromArray[List[Gold]](r.body()))
    else if (r.statusCode() == 404) Right(List.empty)
    else Left(UnexpectedErrorCode)

  private def liveClient(host: String) = {
    val exploreUri = new URI(s"http://$host:8000/explore")
    val licenseUri = new URI(s"http://$host:8000/licenses")
    val digUri = new URI(s"http://$host:8000/dig")
    val cashUri = new URI(s"http://$host:8000/cash")
    ZLayer.fromService[HttpClient, Service] { client =>
      new Service {
        override def explore(area: Area): Task[ExploreReport] = {
          client.sendRequest(exploreUri, area)(jsoniter[ExploreReport])
        }

        override def issueLicense(coins: List[Coin]): Task[License] = {
          client.sendRequest(licenseUri, coins)(jsoniter[License])
        }

        override def listLicenses(): Task[List[License]] = {
          client.sendGetRequest(licenseUri)(jsoniter[List[License]])
        }

        override def dig(req: DigRequest): Task[List[Gold]] = {
          client.sendRequest(digUri, req)(jsoniterDig)
        }

        override def cash(gold: Gold): Task[List[Coin]] = {
          client.sendRequest(cashUri, gold)(jsoniter[List[Coin]])
        }
      }
    }
  }

  def explore(area: Area): ZIO[MineClient with Clock, Nothing, ExploreReport] =
    ZIO.accessM(_.get.explore(area).retry(Schedule.forever).orDie)

  def dig(digRequest: DigRequest): URIO[MineClient with Clock, List[Gold]] =
    ZIO.accessM(_.get.dig(digRequest).retry(Schedule.forever).orDie)

  def issueLicense(coins: List[Coin]): RIO[MineClient with Clock, License] =
    ZIO.accessM(_.get.issueLicense(coins))

  def listLicenses(): URIO[MineClient with Clock, List[License]] =
    ZIO.accessM(_.get.listLicenses().retry(Schedule.forever).orDie)

  def cash(gold: Gold): URIO[MineClient with Clock, List[Coin]] =
    ZIO.accessM(_.get.cash(gold).retry(Schedule.forever).orDie)

}
