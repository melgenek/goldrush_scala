package goldrush

import com.lmax.disruptor.EventHandler

class ParallelHandler[A](id: Int, total: Int, f: A => Unit) extends EventHandler[A] {
  override def onEvent(event: A, sequence: Long, endOfBatch: Boolean): Unit = {
    if (sequence % total == id) {
      f(event)
    }
  }
}

object ParallelHandler {
  def make[A](n: Int)(f: A => Unit): Seq[EventHandler[A]] = {
    (0 until n).map { i =>
      new ParallelHandler[A](i, n, f)
    }
  }
}
