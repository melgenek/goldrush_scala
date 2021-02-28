package goldrush

import goldrush.LicensePool.ExecWithLicense
import goldrush.client.MineClient
import goldrush.models._
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.hotspot.StandardExports
import zio.clock.Clock
import zio.duration._
import zio.stream.{UStream, ZStream}
import zio.{Chunk, ExitCode, Queue, UIO, URIO, ZIO}

import java.io.StringWriter
import java.time.{Duration, LocalTime}
import java.util.Comparator
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong

object Main extends zio.App {
  final val Width = 3500
  final val Step = 2
  final val Host = sys.env.getOrElse("ADDRESS", "localhost")
  final val IsLocal = !sys.env.contains("ADDRESS")
  final val Cpus = Runtime.getRuntime.availableProcessors()
  final val TotalGold = new AtomicLong()

  new StandardExports().register(CollectorRegistry.defaultRegistry)

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    println(s"Starting. Cpus: $Cpus")
    val start = LocalTime.now()

    val layer = MineClient.live(Host)

    val queue = new PriorityBlockingQueue[ExploreReport](50, new Comparator[ExploreReport] {
      override def compare(r1: ExploreReport, r2: ExploreReport): Int = r2.amount.compareTo(r1.amount)
    })

    val program = for {
      _ <- ZStream.tick(if (IsLocal) 10.second else 30.second)
        .drop(1)
        .foreach(_ => debug(start))
        .forkDaemon
      _ <- ZStream.tick(if (IsLocal) 60.second else 9.minutes)
        .drop(1)
        .foreach(_ => printMetrics())
        .forkDaemon

      _ <- areas(Step)
        .mapM { case (x, y) => MineClient.explore(Area(x, y, Step, Step)) }
        .foreach(r => UIO(queue.put(r)))
        .fork

      (wallet, licenses) <- LicensePool.make
      _ <- ZStream.repeat(queue.take())
        .flatMap(r => cells(r.area))
        .mapM { case (x, y) => MineClient.explore(Area(x, y, 1, 1)) }
        .filter(_.amount > 0)
        .mapConcatM(dig(licenses))
        .buffer(128)
        .mapConcatM(MineClient.cash)
        .buffer(128)
        .foreach { coin => wallet.offer(coin).as(TotalGold.incrementAndGet()) }
    } yield ()

    program.provideCustomLayer(layer).exitCode
  }

  def exploreAndDig(wallet: Queue[Coin], execWithLicense: ExecWithLicense)(report: ExploreReport): URIO[MineClient with Clock, Unit] = {
    cells(report.area)
      .foreach { case (x, y) => exploreAndDigCell(wallet, execWithLicense)(x, y) }
  }

  def exploreAndDigCell(wallet: Queue[Coin], execWithLicense: ExecWithLicense)(x: Int, y: Int): URIO[MineClient with Clock, Int] = {
    for {
      cellReport <- MineClient.explore(Area(x, y, 1, 1))
      cellFound <- if (cellReport.amount > 0) digAndExchange(wallet, execWithLicense)(cellReport) else UIO(0)
    } yield cellFound
  }

  def digAndExchange(wallet: Queue[Coin], execWithLicense: ExecWithLicense)(report: ExploreReport): URIO[MineClient with Clock, Int] = {
    for {
      allGold <- dig(???)(report)
      coins <- ZIO.foreachPar(allGold)(MineClient.cash)
      allCoins = coins.flatten
      _ <- ZIO.foreachPar(allCoins)(c => wallet.offer(c))
      _ = TotalGold.addAndGet(allCoins.size.toLong)
    } yield allGold.size
  }

  def dig(licenses: Queue[LicenseLease])(report: ExploreReport): ZIO[MineClient with Clock, Nothing, List[Gold]] = {
    ZIO.foldLeft(1 to 10)(List.empty[Gold]) { case (acc, depth) =>
      if (acc.size >= report.amount) UIO(acc)
      else {
        for {
          lease <- licenses.take
          gold <- MineClient.dig(DigRequest(lease.licenseId, report.area.posX, report.area.posY, depth))
          _ <- lease.requestMore
        } yield acc ++ gold
      }
    }
  }

  def areas(step: Int): UStream[(Int, Int)] = {
    val row = ZStream.iterate(0)(_ + step).take(Width / step)
    row.cross(row)
  }

  def cells(area: Area): UStream[(Int, Int)] = {
    val row = ZStream.iterate(area.posX)(_ + 1).take(area.sizeX)
    val column = ZStream.iterate(area.posY)(_ + 1).take(area.sizeY)
    row.cross(column)
  }

  private def debug(start: LocalTime) = UIO {
    val now = LocalTime.now()
    val timePassed = Duration.between(start, now)
    println(s"$timePassed. Total: ${TotalGold.get()}")
  }

  private def printMetrics() = UIO {
    val writer = new StringWriter()
    TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples())
    println(writer.toString)
  }

  private def printStatsAndGetAverage(reports: Chunk[ExploreReport]): UIO[Float] = UIO {
    val amounts = reports.map(_.amount.toFloat)
    val count = amounts.size
    val sum = amounts.sum
    val max = amounts.max
    val min = amounts.min
    val avg = amounts.sum / amounts.size
    println(s"Count = $count. Sum = $sum. Max = $max. Min = $min. Avg = $avg")
    avg
  }

}
