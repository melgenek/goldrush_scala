package goldrush

import goldrush.client.MineClient
import goldrush.models.{Coin, LicenseLease}
import zio._
import zio.clock.Clock
import zio.duration._
import zio.stream.ZStream

import java.util.concurrent.atomic.AtomicInteger
import scala.util.Random

object LicensePool {
  final val MaxLicenses = 10
  final val ExpensiveCosts = Array(21, 11, 1, 0)
  final val MediumCosts = Array(11, 1, 0)
  final val CheapCosts = Array(1, 0)

  def randomCost: Int = ExpensiveCosts(Random.nextInt(ExpensiveCosts.length))

  final val Licenses = new AtomicInteger()

  def make: URIO[MineClient with Clock, (Queue[Coin], Queue[LicenseLease])] = {
    for {
      wallet <- ZQueue.dropping[Coin](200)
      licenseRequests <- ZQueue.bounded[Unit](MaxLicenses)
      _ <- licenseRequests.offer(()).repeatN(MaxLicenses - 1)
      licenses <- ZQueue.bounded[LicenseLease](200)
//      _ <- ZStream.tick(2.second)
//        .mapM(_ => licenseRequests.size)
//        .foreach(s => UIO(println(s"Requests: $s")))
//        .forkDaemon
      _ <- ZStream.fromQueueWithShutdown(licenseRequests)
        .mapMParUnordered(MaxLicenses) { _ =>
          for {
            currentLeases <- licenses.size
            costs <- UIO {
              if (currentLeases < 30) ExpensiveCosts
              else if (currentLeases < 60) MediumCosts
              else CheapCosts
            }
            coins <- ZIO.foldLeft(costs)(List.empty[Coin]) { case (acc, cost) =>
              if (acc.nonEmpty) ZIO.succeed(acc)
              else wallet.takeN(cost).timeoutTo(List.empty)(identity)(100.nano)
            }
            license <- MineClient.issueLicense(coins)
            _ <- ZIO.foreach((1 to license.digAllowed).toList) { i =>
              if (i == license.digAllowed) licenses.offer(LicenseLease(license.id, licenseRequests.offer(()).unit))
              else licenses.offer(LicenseLease(license.id, UIO.unit))
            }
          } yield Licenses.incrementAndGet()
        }
        .runDrain
        .fork
    } yield (wallet, licenses)
  }

}
