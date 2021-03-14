package goldrush

import goldrush.LicensePool.{GoldSpent, Licenses}
import goldrush.client.MineClient
import goldrush.client.MineClient.ExploreTimeout
import goldrush.models.Coin.Coin
import goldrush.models.Gold.Gold
import goldrush.models._
import zio.clock.Clock
import zio.duration._
import zio.stream.{UStream, ZStream}
import zio.{Chunk, ExitCode, Queue, UIO, URIO, ZIO}

import java.time.LocalTime
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable
import scala.util.Random

object Main extends zio.App {
  final val Width = 3500
  final val Host = sys.env.getOrElse("ADDRESS", "localhost")
  final val IsLocal = !sys.env.contains("ADDRESS")
  final val Cpus = Runtime.getRuntime.availableProcessors()
  final val TotalCoins = new AtomicLong()
  final val GoldFound = new AtomicLong()
  final val TotalGold = new AtomicLong()

  final val Parallelism = if (IsLocal) Cpus else Cpus

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
        .foreach(_ => UIO(metrics.printMetrics()))
        .forkDaemon

      treasureReports <- randomAreas(Area(0, 0, Width, Width), 2)
        .mapMParUnordered(Parallelism * 4)(area => MineClient.explore(area, ExploreTimeout * 2))
        .filterNot(_.isEmpty)
        .mapMParUnordered(Parallelism * 4) { r =>
          //          ZIO.foldLeft(r.cells)((0, mutable.ArrayBuilder.make[ExploreReport])) {
          //            case ((found, reports), area) =>
          //              if (found >= r.amount) UIO((found, reports))
          //              else for {
          //                localReport <- MineClient.explore(area, ExploreTimeout)
          //                newReports = if (localReport.amount > 0) reports += localReport else reports
          //              } yield (found + localReport.amount, newReports)
          //          }
          //                    ZStream.fromIterable(r.cells).foldWhileM((0, mutable.ArrayBuilder.make[ExploreReport]))(_._1 < r.amount) {
          //                      case ((found, reports), area) =>
          //                        for {
          //                          localReport <- MineClient.explore(area, ExploreTimeout)
          //                          newReports = if (localReport.amount > 0) reports += localReport else reports
          //                        } yield (found + localReport.amount, newReports)
          //                    }
          loopExplore(r.amount, 0, r.cells, mutable.ArrayBuilder.make[ExploreReport])
        }
        //                .mapConcat(_._2.result())
        .tap(_ => UIO(TotalGold.incrementAndGet()))
        .take(if (IsLocal) 500 else 22000)
        .runCollect

      treasures = treasureReports.flatten

      _ <- ZStream.fromChunk(treasures)
        .mapMParUnordered(Parallelism)(dig(licenses))
        .mapMParUnordered(Parallelism) { gold =>
          for {
            levelledCoins <- ZIO.foreachPar(gold)(MineClient.cash)
            coins = levelledCoins.flatten
            _ <- wallet.offerAll(coins)
          } yield {
            GoldFound.addAndGet(gold.length)
            TotalCoins.addAndGet(coins.length)
          }
        }
        .runDrain
    } yield ()

    program.provideCustomLayer(layer).exitCode
  }

  def loopExplore(amount: Int, found: Int, cells: List[Area], reports: mutable.ArrayBuilder[ExploreReport]): URIO[MineClient with Clock, Chunk[ExploreReport]] = {
    cells match {
      case cell :: tail if found < amount =>
        for {
          localReport <- MineClient.explore(cell, ExploreTimeout)
          newReports = if (localReport.amount > 0) reports += localReport else reports
          reports <- loopExplore(amount, found + localReport.amount, tail, newReports)
        } yield reports
      case _ => ZIO.succeed(Chunk.fromArray(reports.result()))
    }
  }

  final val DepthRange = 1 to 10

  def dig(licenses: Queue[LicenseLease])(report: ExploreReport): ZIO[MineClient with Clock, Nothing, Array[Gold]] = {
    ZIO.foldLeft(DepthRange)(mutable.ArrayBuilder.make[Gold]) { case (acc, depth) =>
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
    val row = area.posX until (area.posX + area.sizeX) by step
    val shuffledRow = Random.shuffle(row.toVector)
    val column = area.posY until (area.posY + area.sizeY) by step
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
      println(s"$timePassed. Treasures: ${TotalGold.get()}. Found: ${GoldFound.get()} Coins: ${TotalCoins.get()}. Spent: ${GoldSpent.get()}. Licenses total: ${Licenses.get()}. Wallet: $walletSize. Licenses: $licensesSize")
    }
  }

}
