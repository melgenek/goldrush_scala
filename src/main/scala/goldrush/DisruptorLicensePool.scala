package goldrush

import com.lmax.disruptor.dsl.{Disruptor, ProducerType}
import com.lmax.disruptor.util.DaemonThreadFactory
import com.lmax.disruptor.{BlockingWaitStrategy, EventTranslatorOneArg, LiteBlockingWaitStrategy, YieldingWaitStrategy}

import java.util.concurrent.locks.{Condition, Lock, ReentrantLock}

class DisruptorPool[A](bufferSize: Int, producerType: ProducerType) {

  private class Event {
    var value: A = _
  }

  private val translator = new EventTranslatorOneArg[Event, A] {
    override def translateTo(event: Event, sequence: Long, newValue: A): Unit = {
      event.value = newValue
    }
  }

  private val disruptor = new Disruptor[Event](
    () => new Event(),
    bufferSize,
    DaemonThreadFactory.INSTANCE,
    producerType,
    new LiteBlockingWaitStrategy
  )
  private val ringBuffer = disruptor.start()
  private val poller = ringBuffer.newPoller()
  ringBuffer.addGatingSequences(poller.getSequence)

  private val lock = new ReentrantLock
  private val processorNotifyCondition = lock.newCondition

  private def signallAll(): Unit = {
    lock.lock()
    try processorNotifyCondition.signalAll()
    finally lock.unlock()
  }

  def offer(value: A): Unit = {
    ringBuffer.publishEvent(translator, value)
    signallAll()
  }

  def tryOffer(value: A): Unit = {
    if (ringBuffer.tryPublishEvent(translator, value)) {
      signallAll()
    }
  }

  //  def offer(values: Array[A]): Unit = {
  //    ringBuffer.publishEvents(translator, values, 0 , values.length)
  //    ???
  //  }

  def poll(): Option[A] = {
    var res: Option[A] = None
    poller.poll((event: Event, sequence: Long, endOfBatch: Boolean) => {
      res = Some(event.value)
      false
    })
    res
  }

  def take(): A = {
    lock.lock()
    try {
      var res: Option[A] = poll()
      while (res.isEmpty) {
        processorNotifyCondition.await()
        res = poll()
      }
      res.get
    } finally {
      lock.unlock()
    }
  }

  def lag(): Long = {
    ringBuffer.getCursor - ringBuffer.getMinimumGatingSequence
  }

}
