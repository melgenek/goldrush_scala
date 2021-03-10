package goldrush

import goldrush.Main2.LicenseUse
import goldrush.client.{BlockingMineClient, FluxMineClient}
import goldrush.models.{Area, Coin, DigRequest, ExploreReport, Gold}
import reactor.core.publisher.{Flux, FluxSink, Mono, SynchronousSink}
import reactor.core.publisher.FluxSink.OverflowStrategy
import reactor.core.scheduler.Schedulers

import java.time.{Duration, LocalTime}
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ArrayBlockingQueue, Callable, Executors, Semaphore}
import scala.collection.mutable
import scala.jdk.CollectionConverters._

object ReactorMain {
  final val Width = 3500
  final val Host = sys.env.getOrElse("ADDRESS", "localhost")
  final val IsLocal = !sys.env.contains("ADDRESS")
  final val Cpus = Runtime.getRuntime.availableProcessors()
  final val TotalGold = new AtomicLong()

  final val Parallelism = if (IsLocal) 1 else 64

  val client = new FluxMineClient(Host)
//  val blockingClient = new BlockingMineClient(Host)

  final case class CoinWrapper(coin: Coin)

  val licenses = new ArrayBlockingQueue[LicenseUse](128)
  val wallet = new ArrayBlockingQueue[CoinWrapper](128)

  val start = LocalTime.now()
  val started = false

  val step = 2

  final val MaxLicenses = 10

  def main(args: Array[String]): Unit = {
    debug()
    licenseIssuer()
    println(s"Parallelism: $Parallelism. Step: $step.")

    areas(Area(0, 0, Width, Width), step)
      .flatMap(a => client.explore(a), Parallelism)
      .filter(_.amount > 0)
      .concatMapIterable(r => cells(r.area).asJava)
      .flatMap(a => client.explore(a), Parallelism)
      .filter(_.amount > 0)
      .flatMap((r: ExploreReport) => dig(r.area.posX, r.area.posY, r.amount).flatMapIterable(_.asJava), Parallelism)
      .flatMap(gold => client.cash(gold).flatMapIterable(_.asJava))
      .doOnNext { coin =>
        wallet.offer(CoinWrapper(coin))
        TotalGold.incrementAndGet()
      }
      .doOnError(e => println(e.getMessage))
      .`then`()
      .block()
  }

  def dig(x: Int, y: Int, left: Int, gold: List[Gold] = List.empty, depth: Int = 1): Mono[List[Gold]] =
    if (left <= 0 || depth > 10) Mono.just(gold)
    else Mono.fromSupplier(() => licenses.take())
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

    //    runParallel(Cpus)(_ => {
    //      while (true) {
    //        licenseSemaphore.acquire()
    //        val coin = wallet.poll()
    //        val license = blockingClient.issueLicense(Option(coin).map(_.coin))
    //        (1 to license.digAllowed).foreach { i =>
    //          if (i == license.digAllowed) licenses.put(LicenseUse(license.id, () => licenseSemaphore.release()))
    //          else licenses.put(LicenseUse(license.id, () => ()))
    //        }
    //      }
    //    })

    Flux
      .create((fluxSink: FluxSink[Unit]) => {
        while (true) {
          licenseSemaphore.acquire()
          fluxSink.next(())
        }
      })
      .flatMap((_: Unit) => {
        val coins = if (wallet.size() > 30 && licenses.size() < 50) {
          (1 to 11).map(_ => wallet.take()).toList
        } else {
          Option(wallet.poll()).toList
        }
        client.issueLicense(coins.map(_.coin))
          .map { license =>
            if (license.digAllowed == 0) licenseSemaphore.release()
            else (1 to license.digAllowed).foreach { i =>
              if (i == license.digAllowed) licenses.put(LicenseUse(license.id, () => licenseSemaphore.release()))
              else licenses.put(LicenseUse(license.id, () => ()))
            }
          }
      }, MaxLicenses)
      .publishOn(Schedulers.newSingle("licenses"))
      .subscribeOn(Schedulers.boundedElastic())
      .subscribe()
  }

  def debug() = {
    Flux.interval(Duration.ofMillis(0), if (IsLocal) Duration.ofSeconds(2) else Duration.ofSeconds(20))
      .doOnNext { _ =>
        val now = LocalTime.now()
        val timePassed = java.time.Duration.between(start, now)
        println(s"$timePassed. Started: $started. Gold: ${TotalGold.get()}. Licenses: ${licenses.size()}. Wallet: ${wallet.size()}")
      }
      .subscribeOn(Schedulers.boundedElastic())
      .subscribe()
  }

}
