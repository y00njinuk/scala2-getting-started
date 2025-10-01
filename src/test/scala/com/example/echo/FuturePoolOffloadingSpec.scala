package com.example.echo

import com.twitter.conversions.DurationOps._
import com.twitter.util.{Await, Future, FuturePool}
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.concurrent.Eventually
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.time.{Millis, Seconds, Span}

class FuturePoolOffloadingSpec extends AnyFunSuite with Eventually {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Seconds), interval = Span(20, Millis))

  private final case class PoolRun(
      started: AtomicInteger,
      maxRunning: AtomicInteger,
      release: CountDownLatch,
      futures: Seq[Future[Unit]])

  private def submitBlockingTasks(pool: FuturePool, taskCount: Int): PoolRun = {
    val started = new AtomicInteger(0)
    val running = new AtomicInteger(0)
    val maxRunning = new AtomicInteger(0)
    val release = new CountDownLatch(1)

    def updateMax(current: Int): Unit = {
      var done = false
      while (!done) {
        val snapshot = maxRunning.get()
        if (current <= snapshot) done = true
        else done = maxRunning.compareAndSet(snapshot, current)
      }
    }

    val futures = (1 to taskCount).map { _ =>
      pool {
        started.incrementAndGet()
        val inFlight = running.incrementAndGet()
        updateMax(inFlight)
        try release.await()
        finally running.decrementAndGet()
      }
    }

    PoolRun(started, maxRunning, release, futures)
  }

  test("unbounded FuturePool adds threads until all blocking tasks start") {
    val executor = Executors.newCachedThreadPool()
    val pool = FuturePool(executor)
    val run = submitBlockingTasks(pool, taskCount = 12)

    try {
      eventually {
        assert(run.started.get() == 12)
        assert(run.maxRunning.get() == 12)
      }
    } finally {
      run.release.countDown()
      Await.result(Future.collect(run.futures), 5.seconds)
      executor.shutdown()
      executor.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  test("fixed FuturePool limits concurrency and queues overflow work") {
    val executor = Executors.newFixedThreadPool(3)
    val pool = FuturePool(executor)
    val run = submitBlockingTasks(pool, taskCount = 12)

    try {
      eventually {
        assert(run.started.get() == 3)
        assert(run.maxRunning.get() == 3)
      }
      Thread.sleep(100)
      assert(run.started.get() < 12)
      assert(run.maxRunning.get() == 3)
    } finally {
      run.release.countDown()
      Await.result(Future.collect(run.futures), 5.seconds)
      executor.shutdown()
      executor.awaitTermination(5, TimeUnit.SECONDS)
    }
  }
}
