package goldrush.client

import io.prometheus.client.{Collector, CollectorRegistry, Gauge, Histogram}
import sttp.client3.listener.{ListenerBackend, RequestListener}
import sttp.client3.{FollowRedirectsBackend, Identity, Request, Response, SttpBackend}

object MonitoringBackend {

  def apply[F[_], P](delegate: SttpBackend[F, P]): SttpBackend[F, P] = {
    new FollowRedirectsBackend[F, P](
      new ListenerBackend[F, P, Long](
        delegate,
        RequestListener.lift(
          new MonitoringListener,
          delegate.responseMonad
        )
      )
    )
  }

  class MonitoringListener extends RequestListener[Identity, Long] {
    override def beforeRequest(request: Request[_, _]): Identity[Long] = {
      val start = System.nanoTime()
      InFlight.labels(path(request)).inc()
      start
    }

    override def requestException(request: Request[_, _], start: Long, e: Exception): Identity[Unit] = {
      InFlight.labels(path(request)).dec()
      RequestLatencies.labels(path(request), "555").observe(elapsedSeconds(start))
    }

    override def requestSuccessful(request: Request[_, _], response: Response[_], start: Long): Identity[Unit] = {
      InFlight.labels(path(request)).dec()
      RequestLatencies.labels(path(request), response.code.code.toString).observe(elapsedSeconds(start))
    }
  }

  final val Buckets = List(.1, 1)
  final val Labels = List("path", "code")

  val RequestLatencies: Histogram = Histogram
    .build()
    .buckets(Buckets: _*)
    .name("request_latency")
    .labelNames(Labels: _*)
    .help("request_latency")
    .register(CollectorRegistry.defaultRegistry)

  val InFlight: Gauge = Gauge
    .build()
    .name("in_flight_total")
    .labelNames("path")
    .help("in_flight_total")
    .register(CollectorRegistry.defaultRegistry)

  def path(request: Request[_, _]): String = request.uri.path.mkString("/")

  def elapsedSeconds(startNanos: Long): Double =
    (System.nanoTime() - startNanos) / Collector.NANOSECONDS_PER_SECOND

}
