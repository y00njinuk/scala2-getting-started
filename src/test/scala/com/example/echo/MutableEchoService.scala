package com.example.echo

import com.example.echo.thrift.Echo
import com.twitter.util.Future
import java.util.concurrent.atomic.AtomicReference

/**
 * Test helper that lets tests swap the underlying Echo logic at runtime.
 */
final class MutableEchoService(initial: Echo.MethodPerEndpoint) extends Echo.MethodPerEndpoint {
  private val delegate = new AtomicReference[Echo.MethodPerEndpoint](initial)

  def become(next: Echo.MethodPerEndpoint): Unit = delegate.set(next)

  override def ping(msg: String): Future[String] = delegate.get().ping(msg)
}
