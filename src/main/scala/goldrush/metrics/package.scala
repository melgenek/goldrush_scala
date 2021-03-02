package goldrush

import io.prometheus.client.{Collector, CollectorRegistry, Histogram, Summary}
import zio.UIO

package object metrics {

  final val Buckets = List(.1, 1)
  final val RefinedBuckets = List(.01, .05)

  val LicenseAcquisition: Histogram = Histogram
    .build("acquire_license", "acquire_license")
    .buckets(RefinedBuckets: _*)
    .register(CollectorRegistry.defaultRegistry)

  val GoldSummary: Summary = Summary
    .build("gold_summary", "gold_summary")
    .quantile(0.25, 0.01)
    .quantile(0.5, 0.01)
    .quantile(0.9, 0.01)
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
