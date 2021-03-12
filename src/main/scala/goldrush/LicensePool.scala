package goldrush

import goldrush.client.MineClient
import goldrush.models.{Coin, LicenseLease}
import zio._
import zio.clock.Clock
import zio.stm.TSemaphore
import zio.stream.ZStream

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

object LicensePool {
  final val MaxLicenses = 10

  final val Licenses = new AtomicInteger()
  final val GoldSpent = new AtomicLong()

  def make: URIO[MineClient with Clock, (Queue[Coin], Queue[LicenseLease])] = {
    for {
      wallet <- ZQueue.dropping[Coin](128)
      licenses <- ZQueue.bounded[LicenseLease](128)
      semaphore <- TSemaphore.make(MaxLicenses).commit
      _ <- ZStream.repeatEffect(semaphore.acquire.commit)
        .mapMParUnordered(MaxLicenses) { _ =>
          for {
            coins <- wallet.takeUpTo(1)
            license <- MineClient.issueLicense(coins)
            _ <- ZIO.foreach((1 to license.digAllowed).toList) { i =>
              if (i == license.digAllowed) {
                licenses.offer(LicenseLease(license.id,
                  semaphore.release.commit
                ))
              } else licenses.offer(LicenseLease(license.id, UIO.unit))
            }
          } yield {
            Licenses.incrementAndGet()
            GoldSpent.addAndGet(coins.length)
          }
        }
        .runDrain
        .fork
    } yield (wallet, licenses)
  }

}
