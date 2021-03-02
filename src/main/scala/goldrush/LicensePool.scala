package goldrush

import goldrush.client.MineClient
import goldrush.models.{Coin, Gold, License, LicenseLease}
import zio._
import zio.clock.Clock
import zio.stream.ZStream

import java.util.concurrent.atomic.AtomicInteger

object LicensePool {
  final val MaxLicenses = 10

  type WithLicenseCallback = Int => URIO[MineClient with Clock, List[Gold]]
  type ExecWithLicense = WithLicenseCallback => URIO[MineClient with Clock, List[Gold]]

  def makeSimple: UIO[(Queue[Coin], ExecWithLicense)] = {
    for {
      sem <- Semaphore.make(permits = MaxLicenses)
      wallet <- ZQueue.dropping[Coin](MaxLicenses * 10)
      licenseQueue <- ZQueue.dropping[License](MaxLicenses)
    } yield (wallet, (f: WithLicenseCallback) => {
      sem.withPermit {
        for {
          existingLicense <- licenseQueue.poll
          license <- existingLicense.fold {
            for {
              coin <- wallet.poll
              license <- MineClient.issueLicense(coin)
            } yield license
          }(UIO(_))
          res <- f(license.id)
          updatedLicense = license.copy(digUsed = license.digUsed + 1)
          _ <- ZIO.when(!updatedLicense.isUsed)(licenseQueue.offer(updatedLicense))
        } yield res
      }
    })
  }

  final val FreeLicenses = new AtomicInteger()
  final val PaidLicenses = new AtomicInteger()

  def make: URIO[MineClient with Clock, (Queue[Coin], Queue[LicenseLease])] = {
    for {
      wallet <- ZQueue.dropping[Coin](MaxLicenses * 2)
      licenseRequests <- ZQueue.bounded[Unit](MaxLicenses)
      _ <- licenseRequests.offer(()).repeatN(MaxLicenses - 1)
      licenses <- ZQueue.bounded[LicenseLease](MaxLicenses * 5)
      _ <- ZStream.fromQueueWithShutdown(licenseRequests)
        .mapMPar(Main.Cpus) { _ =>
          for {
            coin <- wallet.poll
            license <- MineClient.issueLicense(coin)
            _ <- ZIO.foreach((1 to license.digAllowed).toList) { i =>
              if (i == license.digAllowed) licenses.offer(LicenseLease(license.id, licenseRequests.offer(()).unit))
              else licenses.offer(LicenseLease(license.id, UIO.unit))
            }
          } yield coin.fold(FreeLicenses.incrementAndGet())(_ => PaidLicenses.incrementAndGet())
        }
        .runDrain
        .fork
    } yield (wallet, licenses)
  }

}
