package goldrush

import io.prometheus.client.{Collector, CollectorRegistry, Gauge, Histogram, Summary}
import zio.{UIO, ZIO}

package object metrics {

  final val RefinedBuckets = List(.01, 0.030, 0.040, .05)

  val LicenseAcquisition: Histogram = Histogram
    .build("acquire_license", "acquire_license")
    .buckets(RefinedBuckets: _*)
    .register(CollectorRegistry.defaultRegistry)

  final val Labels = List("path", "code")

  val RequestLatencies: Histogram = Histogram
    .build("request_latency", "request_latency")
    .buckets(RefinedBuckets: _*)
    .labelNames(Labels: _*)
    .register(CollectorRegistry.defaultRegistry)

  val InFlight: Gauge = Gauge
    .build("in_flight_total", "in_flight_total")
    .labelNames("path")
    .register(CollectorRegistry.defaultRegistry)

  def measure[R, E, A](f: => ZIO[R, E, A], histogram: Histogram): ZIO[R, E, A] = {
    for {
      start <- UIO(System.nanoTime())
      a <- f
      _ = histogram.observe(elapsedSeconds(start))
    } yield a
  }

  def elapsedSeconds(startNanos: Long): Double =
    (System.nanoTime() - startNanos) / Collector.NANOSECONDS_PER_SECOND

}
