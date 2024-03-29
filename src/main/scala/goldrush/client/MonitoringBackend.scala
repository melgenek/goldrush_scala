package goldrush.client

import goldrush.metrics.{InFlight, RequestLatencies, elapsedSeconds}
import sttp.capabilities.zio.ZioStreams
import sttp.capabilities.{Effect, WebSockets}
import sttp.client3.httpclient.zio.SttpClient
import sttp.client3.{DelegateSttpBackend, DeserializationException, HttpError, Identity, Request, Response}
import zio.{Task, UIO}

object MonitoringBackend {

  def wrap(delegate: SttpClient.Service): SttpClient.Service =
    new DelegateSttpBackend[Task, ZioStreams with WebSockets](delegate) {
      override def send[T, R >: ZioStreams with WebSockets with Effect[Task]](request: Request[T, R]): Task[Response[T]] = {
        for {
          start <- UIO(MonitoringListener.beforeRequest(request))
          response <- delegate.send(request)
            .unrefineTo[Throwable]
            .tapError(e => UIO(MonitoringListener.requestException(request, start, e)))
            .tap(response => UIO(MonitoringListener.requestSuccessful(request, response, start)))
        } yield response
      }
    }

  object MonitoringListener {
    def beforeRequest(request: Request[_, _]): Identity[Long] = {
      val start = System.nanoTime()
      InFlight.labels(path(request)).inc()
      start
    }

    def requestException(request: Request[_, _], start: Long, e: Throwable): Identity[Unit] = {
      InFlight.labels(path(request)).dec()
      val code = e match {
        case HttpError(_, code) => code.code
        case _: DeserializationException[_] => 554
        case _ => 555
      }
      RequestLatencies.labels(path(request), code.toString).observe(elapsedSeconds(start))
    }

    def requestSuccessful(request: Request[_, _], response: Response[_], start: Long): Identity[Unit] = {
      InFlight.labels(path(request)).dec()
      RequestLatencies.labels(path(request), response.code.code.toString).observe(elapsedSeconds(start))
    }
  }

  def path(request: Request[_, _]): String = request.uri.path.mkString("/")

}
