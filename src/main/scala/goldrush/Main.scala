package goldrush

import goldrush.LicensePool.{GoldSpent, Licenses}
import goldrush.client.MineClient
import goldrush.client.MineClient.ExploreTimeout
import goldrush.models.Coin.Coin
import goldrush.models.Gold.Gold
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
import scala.collection.mutable
import scala.util.Random

object Main extends zio.App {
  final val Width = 3500
  final val Host = sys.env.getOrElse("ADDRESS", "localhost")
  final val IsLocal = !sys.env.contains("ADDRESS")
  final val Cpus = Runtime.getRuntime.availableProcessors()
  final val TotalGold = new AtomicLong()

  final val Parallelism = Cpus

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    println(s"Starting. Cpus: $Cpus. Parallelism: $Parallelism. Host: $Host")
    val start = LocalTime.now()

    val layer = MineClient.live(Host)

    val program = for {
      (wallet, licenses) <- LicensePool.make

      _ <- ZStream.tick(if (IsLocal) 2.second else 30.second)
        .drop(1)
        .foreach(_ => debug(start, wallet, licenses))
        .forkDaemon
      _ <- ZStream.tick(if (IsLocal) 45.second else 9.minutes)
        .drop(1)
        .foreach(_ => printMetrics())
        .forkDaemon

      _ <- randomAreas(Area(0, 0, Width, Width), 2)
        .mapMParUnordered(Parallelism)(area => MineClient.explore(area, ExploreTimeout * 2))
        .filterNot(_.isEmpty)
        .mapMParUnordered(Parallelism) { r =>
          areas(r.area, 1).foldWhileM(0)(_ < r.amount) { (found, area) =>
            for {
              localReport <- MineClient.explore(area, ExploreTimeout)
              _ <- if (localReport.amount > 0) {
                for {
                  gold <- dig(licenses)(localReport)
                  levelledCoins <- ZIO.foreachPar(gold)(MineClient.cash)
                  coins = levelledCoins.flatten
                  _ <- wallet.offerAll(coins)
                } yield TotalGold.addAndGet(coins.length)
              } else UIO.unit
            } yield found + localReport.amount
          }
        }
        .runDrain
    } yield ()

    program.provideCustomLayer(layer).exitCode
  }

  def dig(licenses: Queue[LicenseLease])(report: ExploreReport): ZIO[MineClient with Clock, Nothing, Array[Gold]] = {
    ZIO.foldLeft(1 to 10)(mutable.ArrayBuilder.make[Gold]) { case (acc, depth) =>
      if (acc.length >= report.amount) UIO(acc)
      else {
        for {
          lease <- metrics.measure(licenses.take, metrics.LicenseAcquisition)
          gold <- MineClient.dig(DigRequest(lease.licenseId, report.area.posX, report.area.posY, depth))
          _ <- lease.requestMore
        } yield acc.addAll(gold)
      }
    }.map(_.result())
  }

  def randomAreas(area: Area, step: Int): UStream[Area] = {
    val row = area.posX until (area.sizeX / step) by step
    val shuffledRow = Random.shuffle(row.toVector)
    val column = area.posY until (area.sizeY / step) by step
    val shuffledColumn = Random.shuffle(column.toVector)
    val areas = for {
      x <- shuffledRow
      y <- shuffledColumn
    } yield Area(x, y, step, step)

    ZStream.fromIterable(areas)
  }

  def areas(area: Area, step: Int): UStream[Area] = {
    val row = ZStream.iterate(area.posX)(_ + step).take(area.sizeX / step)
    val column = ZStream.iterate(area.posY)(_ + step).take(area.sizeY / step)
    row.cross(column).map { case (x, y) => Area(x, y, step, step) }
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
