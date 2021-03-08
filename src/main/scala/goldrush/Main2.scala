package goldrush

import com.lmax.disruptor.{BlockingWaitStrategy, ExceptionHandler, LiteBlockingWaitStrategy, YieldingWaitStrategy}
import com.lmax.disruptor.dsl.{Disruptor, ProducerType}
import com.lmax.disruptor.util.DaemonThreadFactory
import goldrush.client.BlockingMineClient
import goldrush.models._

import java.time.LocalTime
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.{ArrayBlockingQueue, Executors, Semaphore, TimeUnit}

object Main2 {
  final val Width = 3500
  final val Host = sys.env.getOrElse("ADDRESS", "localhost")
  final val IsLocal = !sys.env.contains("ADDRESS")
  final val Cpus = Runtime.getRuntime.availableProcessors()
  final val TotalGold = new AtomicLong()

  final val Parallelism = Cpus * 8

  val client = new BlockingMineClient(Host)

  class MineEvent {
    var coords: (Int, Int) = _
    var exploreReport: ExploreReport = _
    var gold = List.empty[Gold]

    def clear(): Unit = {
      coords = null
      exploreReport = null
      gold = List.empty[Gold]
    }
  }

  private val disruptor = new Disruptor[MineEvent](
    () => new MineEvent(),
    Parallelism * 4,
    DaemonThreadFactory.INSTANCE,
    ProducerType.SINGLE,
    new LiteBlockingWaitStrategy
  )

  final case class LicenseUse(licenseId: Int, callback: () => Unit)

  val licenseSemaphore = new Semaphore(10)

  def main(args: Array[String]): Unit = {
    val licenses = new ArrayBlockingQueue[LicenseUse](128)
    val wallet = new ArrayBlockingQueue[Coin2](128)
    val start = LocalTime.now()
    var started = false
    val scheduler = Executors.newSingleThreadScheduledExecutor()
    scheduler.scheduleAtFixedRate(() => {
      val now = LocalTime.now()
      val timePassed = java.time.Duration.between(start, now)
      println(s"$timePassed. Started: $started. Gold: ${TotalGold.get()}. Licenses: ${licenses.size()}. Wallet: ${wallet.size()}")
    }, 0, if (IsLocal) 2 else 20, TimeUnit.SECONDS)
    scheduler.schedule(new Runnable {
      override def run(): Unit = {
        metrics.printMetrics()
      }
    }, if (IsLocal) 40 else 500, TimeUnit.SECONDS)

    client.healthCheck()
    started = true

    runParallel(Cpus)(() => {
      while (true) {
        licenseSemaphore.acquire()
        val coin = wallet.poll()

        val license = client.issueLicense(Option(coin).map(c => Coin(c.value)))
        (1 to license.digAllowed).foreach { i =>
          if (i == license.digAllowed) licenses.put(LicenseUse(license.id, () => licenseSemaphore.release()))
          else licenses.put(LicenseUse(license.id, () => ()))
        }
      }
    })

    disruptor.setDefaultExceptionHandler(new ExceptionHandler[MineEvent] {
      override def handleEventException(ex: Throwable, sequence: Long, event: MineEvent): Unit = {
        ex.printStackTrace()
      }

      override def handleOnStartException(ex: Throwable): Unit = {}

      override def handleOnShutdownException(ex: Throwable): Unit = {}
    })

    disruptor
      .andThen(Parallelism) { event =>
        val exploreReport = client.explore(Area(event.coords._1, event.coords._2, 1, 1))
        event.exploreReport = exploreReport
      }
      .andThen(Parallelism) { event =>
        if (event.exploreReport.amount > 0) {
          var left = event.exploreReport.amount
          for (depth <- 1 to 10 if left > 0) {
            val license = metrics.measureBlocking(licenses.take(), metrics.LicenseAcquisition)
            val gold = client.dig(DigRequest(license.licenseId, event.coords._1, event.coords._2, depth))
            license.callback()
            if (gold.nonEmpty) {
              event.gold ++= gold
              left -= 1
            }
          }
        }
      }
      .andThen(Parallelism) { event =>
        for {
          gold <- event.gold
          coin <- client.cash(gold)
        } {
          TotalGold.incrementAndGet()
          wallet.offer(Coin2(coin.value))
        }
        event.clear()
      }

    val ringBuffer = disruptor.start()

    for {
      x <- 0 until Width
      y <- 0 until Width
    } {
      ringBuffer.publishEvent((newEvent: MineEvent, _: Long) => {
        newEvent.coords = (x, y)
      })
    }
  }

}
