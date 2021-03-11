package goldrush

import goldrush.Main2.LicenseUse
import goldrush.client.{BlockingMineClient, FluxMineClient, TrueFluxMineClient}
import goldrush.models._
import io.prometheus.client.hotspot.ThreadExports
import org.slf4j.impl.SimpleLogger
import reactor.core.publisher.FluxSink.OverflowStrategy
import reactor.core.publisher.{Flux, FluxSink, Mono}
import reactor.core.scheduler.Schedulers

import java.time.{Duration, LocalTime}
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ArrayBlockingQueue, Semaphore}
import scala.jdk.CollectionConverters._

object ReactorMain {

  System.setProperty(SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "error")
  new ThreadExports().register()

  final val Width = 3500
  final val Host = sys.env.getOrElse("ADDRESS", "localhost")
  final val IsLocal = !sys.env.contains("ADDRESS")
  final val Cpus = Runtime.getRuntime.availableProcessors()
  final val TotalGold = new AtomicLong()

  final val Parallelism = if (IsLocal) 1 else 64

  val client = new TrueFluxMineClient(Host)
  val blockingClient = new BlockingMineClient(Host)

  final case class CoinWrapper(coin: Coin)

  val licenses = new ArrayBlockingQueue[LicenseUse](128)
  val wallet = new ArrayBlockingQueue[CoinWrapper](128)

  val start = LocalTime.now()
  var started = false

  val step = 2

  final val MaxLicenses = 10

  var lastArea: Area = _

  def main(args: Array[String]): Unit = {
    debug()
    licenseIssuer()
    println(s"Parallelism: $Parallelism. Step: $step.")

    client.healthCheck.block()
    started = true

    areas(Area(0, 0, Width, Width), 1)
      .publishOn(Schedulers.boundedElastic())
      .doOnNext(a => lastArea = a)
      .flatMap(a => client.explore(a), Parallelism)
      .filter(_.amount > 0)
      .concatMapIterable(r => cells(r.area).asJava)
      .flatMap(a => client.explore(a), Parallelism)
      .filter(_.amount > 0)
      .flatMap((r: ExploreReport) => dig(r.area.posX, r.area.posY, r.amount).flatMapIterable(_.asJava), Parallelism)
      .flatMap((gold: Gold) => client.cash(gold).flatMapIterable(_.asJava), Parallelism)
      .doOnNext { coin =>
        wallet.offer(CoinWrapper(coin))
        TotalGold.incrementAndGet()
      }
      .doOnError(e => println(e.getMessage))
      .subscribeOn(Schedulers.boundedElastic())
      .`then`()
      .block()
  }

  def dig(x: Int, y: Int, left: Int, gold: List[Gold] = List.empty, depth: Int = 1): Mono[List[Gold]] =
    if (left <= 0 || depth > 10) Mono.just(gold)
    else Mono.defer(() => Mono.fromSupplier(() => {
      licenses.take()
    }).publishOn(Schedulers.boundedElastic()))
      .flatMap { license =>
        client.dig(DigRequest(license.licenseId, x, y, depth))
          .doOnNext(_ => license.callback())
      }
      .flatMap { newGold => dig(x, y, left - newGold.length, gold ++ newGold, depth + 1) }

  def areas(area: Area, step: Int): Flux[Area] = {
    Flux.create((t: FluxSink[Area]) => {
      for {
        x <- area.posX until (area.posX + area.sizeX) by step
        y <- area.posY until (area.posY + area.sizeY) by step
      } {
        t.next(Area(x, y, step, step))
      }
    }, OverflowStrategy.BUFFER)
  }

  def cells(area: Area) = {
    for {
      x <- area.posX until (area.posX + area.sizeX)
      y <- area.posY until (area.posY + area.sizeY)
    } yield Area(x, y, 1, 1)
  }

  def licenseIssuer() = {
    val licenseSemaphore = new Semaphore(10)

    runParallel(Cpus)(_ => {
      while (true) {
        licenseSemaphore.acquire()
        val coins = if (wallet.size() > 30 && licenses.size() < 50) {
          (1 to 11).map(_ => wallet.take()).toList
        } else {
          Option(wallet.poll()).toList
        }
        val license = blockingClient.issueLicense(coins.map(_.coin))
        (1 to license.digAllowed).foreach { i =>
          if (i == license.digAllowed) licenses.put(LicenseUse(license.id, () => licenseSemaphore.release()))
          else licenses.put(LicenseUse(license.id, () => ()))
        }
      }
    })
  }

  def debug() = {
    Flux.interval(Duration.ofMillis(0), if (IsLocal) Duration.ofSeconds(2) else Duration.ofSeconds(20))
      .publishOn(Schedulers.boundedElastic())
      .doOnNext { _ =>
        val now = LocalTime.now()
        val timePassed = java.time.Duration.between(start, now)
        println(s"$timePassed. Last area: $lastArea. Started: $started. Gold: ${TotalGold.get()}. Licenses: ${licenses.size()}. Wallet: ${wallet.size()}")
      }
      .subscribeOn(Schedulers.boundedElastic())
      .subscribe()
    Flux.interval(if (IsLocal) Duration.ofSeconds(30) else Duration.ofMinutes(9))
      .publishOn(Schedulers.boundedElastic())
      .doOnNext { _ =>
        metrics.printMetrics()
      }
      .subscribeOn(Schedulers.boundedElastic())
      .subscribe()
  }

}
