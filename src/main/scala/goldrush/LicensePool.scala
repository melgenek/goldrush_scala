package goldrush

import goldrush.client.MineClient
import goldrush.models.{Coin, License, LicenseLease}
import zio._
import zio.clock.Clock
import zio.duration._
import zio.stream.ZStream

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.util.Random

object LicensePool {
  final val MaxLicenses = 10
  final val ExpensiveCosts = Array(21, 11, 1, 0)
  final val MediumCosts = Array(11, 1, 0)
  final val CheapCosts = Array(1, 0)

  def randomCost: Int = ExpensiveCosts(Random.nextInt(ExpensiveCosts.length))

  final val Licenses = new AtomicInteger()
  final val GoldSpent = new AtomicLong()

  def make: URIO[MineClient with Clock, (Queue[Coin], Queue[LicenseLease])] = {
    for {
      wallet <- ZQueue.dropping[Coin](200)
      licenses <- ZQueue.bounded[LicenseLease](200)
      ref <- Ref.make(0)
      _ <- ZStream.repeatEffect(ref.get)
        .filter(_ <= MaxLicenses)
        .tap(_ => ref.updateAndGet(_ + 1))
        .mapMParUnordered(MaxLicenses) { _ =>
          for {
//            currentLeases <- licenses.size
//            costs <- UIO {
//              if (currentLeases < 50) ExpensiveCosts
//              else if (currentLeases < 100) MediumCosts
//              else CheapCosts
//            }
//            coins <- ZIO.foldLeft(costs)(List.empty[Coin]) { case (acc, cost) =>
//              if (acc.nonEmpty) ZIO.succeed(acc)
//              else wallet.takeN(cost).timeoutTo(List.empty)(identity)(100.nano)
//            }
            coins <- wallet.takeUpTo(1)
            license <- MineClient.issueLicense(coins)
            _ <- ZIO.foreach((1 to license.digAllowed).toList) { i =>
              if (i == license.digAllowed) {
                licenses.offer(LicenseLease(license.id,
                  ref.update(_ - 1)
                ))
              } else licenses.offer(LicenseLease(license.id, UIO.unit))
            }
          } yield ()
        }
        .runDrain
        .fork
    } yield (wallet, licenses)
  }

}
