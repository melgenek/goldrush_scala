package goldrush

import goldrush.LicensePool.{ExecWithLicense, FreeLicenses, PaidLicenses}
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
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

class Stats {
  private val min = new AtomicInteger()
  private val max = new AtomicInteger()

  private val total = new AtomicLong()
  private val sum = new AtomicLong()

  def observe(cellCount: Int): Unit = {
    if (min.get() > cellCount) min.set(cellCount)
    if (max.get() < cellCount) max.set(cellCount)

    total.incrementAndGet()
    sum.addAndGet(cellCount.toLong)
  }

  override def toString: String = {
    val t = total.get()
    val s = sum.get()
    val avg = s.toDouble / t.toDouble
    s"Total: $t. Sum: $s. Avg: $avg. Max: ${max.get()}. Min: ${min.get()}"
  }
}

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
    val stats = new Stats

    val layer = MineClient.live(Host)

    val program = for {
      _ <- ZStream.tick(if (IsLocal) 30.second else 30.second)
        .drop(1)
        .foreach(_ => debug(start, stats))
        .forkDaemon
      _ <- ZStream.tick(if (IsLocal) 60.second else 9.minutes)
        .drop(1)
        .foreach(_ => printMetrics())
        .forkDaemon

      (wallet, licenses) <- LicensePool.make
      _ <- areas(Step)
        .mapMPar(Cpus) { case (x, y) => MineClient.explore(Area(x, y, Step, Step)) }
        .filterNot(_.isEmpty)
        .tap(r => UIO(stats.observe(r.amount)))
        .flatMap(r => cells(r.area))
        .mapMPar(Cpus) { case (x, y) => MineClient.explore(Area(x, y, 1, 1)) }
        .filterNot(_.isEmpty)
        .mapMPar(Cpus)(dig(licenses))
        .mapConcat(identity)
        .mapMPar(Cpus)(MineClient.cash)
        .mapConcat(identity)
        .bufferDropping(100)
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
          lease <- metrics.measure(licenses.take, metrics.LicenseAcquisition)
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

  private def debug(start: LocalTime, stats: Stats) = UIO {
    val now = LocalTime.now()
    val timePassed = Duration.between(start, now)
    println(s"$timePassed. Total gold: ${TotalGold.get()}. Free: ${FreeLicenses.get()}. Paid: ${PaidLicenses.get()}")
    println(stats)
  }

  private def printMetrics() = UIO {
    val writer = new StringWriter()
    TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples())
    val output = writer.toString.split("\n")
      .filterNot(line => line.startsWith("#") || line.contains("_created"))
      .mkString("\n")
    println(output)
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
