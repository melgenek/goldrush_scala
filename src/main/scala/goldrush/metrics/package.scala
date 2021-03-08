package goldrush

import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.{Collector, CollectorRegistry, Gauge, Histogram, Summary}
import zio.{UIO, ZIO}

import java.io.StringWriter

package object metrics {

  final val RefinedBuckets = List(0.030, .05, .1, 1, 5)

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

  def measureBlocking[A](f: => A, histogram: Histogram): A = {
    val start = System.nanoTime()
    val res = f
    histogram.observe(elapsedSeconds(start))
    res
  }

  def printMetrics(): Unit = {
    val writer = new StringWriter()
    TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples())
    val output = writer.toString.split("\n")
      .filterNot(line => line.startsWith("#") || line.contains("_created"))
      .mkString("\n")
    println(output)
  }

}
