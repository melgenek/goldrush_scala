import com.lmax.disruptor.dsl.{Disruptor, EventHandlerGroup}

package object goldrush {

  implicit class DisruptorOps[A](val disruptor: Disruptor[A]) extends AnyVal {
    def andThen(n: Int)(f: A => Unit): EventHandlerGroup[A] =
      disruptor.handleEventsWith(ParallelHandler.make[A](n)(f): _*)
  }

  implicit class EventHandlerGroupOps[A](val disruptor: EventHandlerGroup[A]) extends AnyVal {
    def andThen(n: Int)(f: A => Unit): EventHandlerGroup[A] =
      disruptor.`then`(ParallelHandler.make[A](n)(f): _*)
  }

  def runParallel(n: Int)(f: () => Unit): Unit = {
    (0 until n).foreach { _ =>
      new Thread(() => {
        f()
      }).start()
    }
  }

}
