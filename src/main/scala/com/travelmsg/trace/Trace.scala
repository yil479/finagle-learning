package com.travelmsg.trace

import com.twitter.finagle.context.Contexts

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._

/**
 * A mutable, thread-safe buffer that filters append short notes to as an
 * event flows through the pipeline. `ConcurrentLinkedQueue`, not a plain
 * `List`, because multiple filters could in principle touch this
 * concurrently - same reasoning as the TrieMaps in FrequencyCapFilter and
 * PriorityArbitrationFilter.
 */
class TraceRecorder {
  private val entries = new ConcurrentLinkedQueue[String]()

  def record(message: String): Unit = {
    entries.add(message)
    ()
  }

  def toList: List[String] = entries.asScala.toList
}

/**
 * Request-scoped trace recording, built on Finagle's `Contexts.local` -
 * the exact mechanism CLAUDE.md's gotchas section named from the start
 * ("Request-scoped context... uses Finagle's Local/Contexts, not MDC or a
 * ThreadLocal") but nothing in this project has used until now.
 *
 * Why this instead of changing every Filter's return type from `Decision`
 * to `(Decision, List[String])`: a `Local` survives being passed through
 * `.map`/`.flatMap` chains the same way a request ID would in a real
 * system, so every Filter can just call `Trace.record(...)` at the point
 * it decides something, with no change to its method signature or how it
 * composes with `andThen`. Spring comparison: closer to a request-scoped
 * bean than a ThreadLocal - it's still one value per in-flight request,
 * but one that correctly follows the request across async hops instead of
 * being tied to whatever thread happens to be running at a given moment.
 *
 * `record` is deliberately silent when called outside an active
 * `withRecording` scope (real, non-dry-run requests never open one) - so
 * every Filter can call it unconditionally, with zero behavior or
 * performance difference on the normal path. Only the dry-run endpoint
 * actually pays for this.
 */
object Trace {

  private val Key = Contexts.local.newKey[TraceRecorder]()

  def withRecording[R](fn: => R): R =
    Contexts.local.let(Key, new TraceRecorder)(fn)

  def record(message: String): Unit =
    Contexts.local.get(Key).foreach(_.record(message))

  def current: List[String] =
    Contexts.local.get(Key).map(_.toList).getOrElse(Nil)
}
