//package goldrush.client
//
//import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray, readFromStream}
//import goldrush.Main
//import goldrush.models._
//import zio.clock.Clock
//import zio.{RIO, Schedule, Task, URIO, ZIO, ZLayer}
//import zio.duration._
//
//import java.io.InputStream
//import java.net.URI
//import java.net.http.{HttpClient, HttpResponse}
//
//object MineClient {
//
//  trait Service {
//    def explore(area: Area, timeout: Duration): RIO[Clock, ExploreReport]
//
//    def issueLicense(coin: List[Coin]): RIO[Clock, License]
//
//    def listLicenses(): RIO[Clock, List[License]]
//
//    def dig(req: DigRequest): RIO[Clock, List[Gold]]
//
//    def cash(gold: Gold): RIO[Clock, List[Coin]]
//  }
//
//  def live(host: String) =
//    ZLayer.succeed(HttpClient.newHttpClient()) >+> MineClient.liveClient(host)
//
//  final val EmptyGoldList = List.empty[Gold]
//
//  final val DigTimeout = if (Main.IsLocal) 400.millis else 30.millis
//  final val ExploreTimeout = if (Main.IsLocal) 400.millis else 30.millis
//  final val CashTimeout = if (Main.IsLocal) 400.millis else 100.millis
//
//  private def liveClient(host: String) = {
//    val exploreUri = new URI(s"http://$host:8000/explore")
//    val licenseUri = new URI(s"http://$host:8000/licenses")
//    val digUri = new URI(s"http://$host:8000/dig")
//    val cashUri = new URI(s"http://$host:8000/cash")
//    ZLayer.fromService[HttpClient, Service] { client =>
//      new Service {
//        override def explore(area: Area, timeout: Duration): RIO[Clock, ExploreReport] = {
//          client.sendRequest(exploreUri, area, timeout)(jsoniter[ExploreReport])
//        }
//
//        override def issueLicense(coins: List[Coin]): RIO[Clock, License] = {
//          client.sendRequest(licenseUri, coins)(jsoniter[License])
//        }
//
//        override def listLicenses(): RIO[Clock, List[License]] = {
////          client.sendGetRequest(licenseUri)(jsoniter[List[License]])
//          ???
//        }
//
//        def digJsoniter(r: HttpResponse[InputStream]): Either[Throwable, List[Gold]] =
//          if (r.statusCode() == 200) Right(readFromStream[List[Gold]](r.body()))
//          else if (r.statusCode() == 404) Right(List.empty)
//          else if (r.statusCode() == 422) Right(List.empty)
//          else if (r.statusCode() == 403) Right(List.empty)
//          else Left(UnexpectedErrorCode)
//
//        override def dig(req: DigRequest): RIO[Clock, List[Gold]] = {
//          client.sendRequest(digUri, req, DigTimeout)(digJsoniter)
//        }
//
//        def cashJsoniter(r: HttpResponse[InputStream]): Either[Throwable, List[Coin]] =
//          if (r.statusCode() == 200) Right(readFromStream[List[Coin]](r.body()))
//          else if (r.statusCode() == 409) Right(List.empty)
//          else if (r.statusCode() == 404) Right(List.empty)
//          else Left(UnexpectedErrorCode)
//
//        override def cash(gold: Gold): RIO[Clock, List[Coin]] = {
//          client.sendRequest(cashUri, gold, CashTimeout)(cashJsoniter)
//        }
//      }
//    }
//  }
//
//  def explore(area: Area, timeout: Duration): ZIO[MineClient with Clock, Nothing, ExploreReport] =
//    ZIO.accessM(_.get.explore(area, timeout).retry(Schedule.forever).orDie)
//
//  def dig(digRequest: DigRequest): URIO[MineClient with Clock, List[Gold]] =
//    ZIO.accessM(_.get.dig(digRequest).retry(Schedule.forever).orDie)
//
//  def issueLicense(coins: List[Coin]): RIO[MineClient with Clock, License] =
//    ZIO.accessM(_.get.issueLicense(coins).retry(Schedule.forever).orDie)
//
//  def listLicenses(): URIO[MineClient with Clock, List[License]] =
//    ZIO.accessM(_.get.listLicenses().retry(Schedule.forever).orDie)
//
//  def cash(gold: Gold): URIO[MineClient with Clock, List[Coin]] =
//    ZIO.accessM(_.get.cash(gold).retry(Schedule.forever).orDie)
//
//}
