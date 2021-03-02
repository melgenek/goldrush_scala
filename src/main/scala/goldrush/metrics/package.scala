package goldrush

import goldrush.client.MonitoringBackend.Labels
import io.prometheus.client.{Collector, CollectorRegistry, Histogram}
import zio.{UIO, ZIO}

package object metrics {

  final val Buckets = List(.1, 1)
  val LicenseAcquisition: Histogram = Histogram
    .build()
    .buckets(Buckets: _*)
    .name("acquire_license")
    .help("acquire_license")
    .register(CollectorRegistry.defaultRegistry)

  def measure[A](f: => UIO[A], histogram: Histogram): UIO[A] = {
    for {
      start <- UIO(System.nanoTime())
      a <- f
      _ = histogram.observe(elapsedSeconds(start))
    } yield a
  }

  def elapsedSeconds(startNanos: Long): Double =
    (System.nanoTime() - startNanos) / Collector.NANOSECONDS_PER_SECOND

}
