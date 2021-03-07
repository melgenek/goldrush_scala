package goldrush

import goldrush.LicensePool.{GoldSpent, Licenses}
import goldrush.client.MineClient
import goldrush.client.MineClient.ExploreTimeout
import goldrush.models._
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import zio.clock.Clock
import zio.duration._
import zio.stream.{UStream, ZStream}
import zio.{ExitCode, Queue, UIO, URIO, ZIO}

import java.io.StringWriter
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicLong

object Main extends zio.App {
  final val Width = 3500
  final val Host = sys.env.getOrElse("ADDRESS", "localhost")
  final val IsLocal = !sys.env.contains("ADDRESS")
  final val Cpus = Runtime.getRuntime.availableProcessors()
  final val TotalGold = new AtomicLong()

  final val Parallelism = Cpus * 8

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    println(s"Starting. Cpus: $Cpus. Host: $Host")
    val start = LocalTime.now()

    val layer = MineClient.live(Host)

    val program = for {
      (wallet, licenses) <- LicensePool.make

      _ <- ZStream.tick(if (IsLocal) 2.second else 120.second)
        .drop(1)
        .foreach(_ => debug(start, wallet, licenses))
        .forkDaemon
      _ <- ZStream.tick(if (IsLocal) 45.second else 9.minutes)
        .drop(1)
        .foreach(_ => printMetrics())
        .forkDaemon

      _ <- areas(Area(0, 0, Width, Width), 2)
        .mapMParUnordered(Parallelism) { case (x, y) => MineClient.explore(Area(x, y, 2, 2), ExploreTimeout * 3) }
        .filterNot(_.isEmpty)
        .flatMap(r => areas(r.area, 1))
        .mapMParUnordered(Parallelism) { case (x, y) => MineClient.explore(Area(x, y, 1, 1), ExploreTimeout) }
        .filterNot(_.isEmpty)
        .mapMParUnordered(Parallelism)(dig(licenses))
        .mapConcat(identity)
        .mapMParUnordered(Parallelism)(MineClient.cash)
        .mapConcat(identity)
        .foreach { coin => wallet.offer(coin).as(TotalGold.incrementAndGet()) }
    } yield ()

    program.provideCustomLayer(layer).exitCode
  }

  def dig(licenses: Queue[LicenseLease])(report: ExploreReport): ZIO[MineClient with Clock, Nothing, List[Gold]] = {
    ZIO.foldLeft(1 to 10)(List.empty[Gold]) { case (acc, depth) =>
      if (acc.size >= report.amount) UIO(acc)
      else {
        for {
          lease <- metrics.measure(licenses.take, metrics.LicenseAcquisition)
          gold <- MineClient.dig(DigRequest(lease.licenseId, report.area.posX, report.area.posY, depth))
          _ <- lease.requestMore
        } yield acc ++ gold
      }
    }
  }

  def areas(area: Area, step: Int): UStream[(Int, Int)] = {
    val row = ZStream.iterate(area.posX)(_ + step).take(area.sizeX / step)
    val column = ZStream.iterate(area.posY)(_ + step).take(area.sizeY / step)
    row.cross(column)
  }

  private def debug(start: LocalTime, wallet: Queue[Coin], licenses: Queue[LicenseLease]) = {
    for {
      walletSize <- wallet.size
      licensesSize <- licenses.size
    } yield {
      val now = LocalTime.now()
      val timePassed = java.time.Duration.between(start, now)
      println(s"$timePassed. Total gold: ${TotalGold.get()}. Spent: ${GoldSpent.get()}. Licenses total: ${Licenses.get()}. Wallet: $walletSize. Licenses: $licensesSize")
    }
  }

  private def printMetrics() = UIO {
    val writer = new StringWriter()
    TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples())
    val output = writer.toString.split("\n")
      .filterNot(line => line.startsWith("#") || line.contains("_created"))
      .mkString("\n")
    println(output)
  }

}
