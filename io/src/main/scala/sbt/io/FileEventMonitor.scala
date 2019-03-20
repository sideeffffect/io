/*
 * sbt IO
 *
 * Copyright 2011 - 2019, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 */

package sbt.io

import java.nio.file.{ Path => JPath }
import java.util.concurrent.{ ArrayBlockingQueue, ConcurrentHashMap, TimeUnit }

import sbt.io.FileTreeDataView.{ Entry, Observable, Observer }

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.duration._

/**
 * Provides a blocking interface for awaiting file events.
 * @tparam T the type of [[FileTreeDataView.Entry.value]] instances
 */
private[sbt] trait FileEventMonitor[+T] extends AutoCloseable {

  /**
   * Block for the specified duration until an event is emitted or a timeout occurs.
   * @param duration the timeout (can be infinite)
   * @return a sequence of [[FileEventMonitor.Event]] instances.
   */
  def poll(duration: Duration): Seq[FileEventMonitor.Event[T]]
}
private[sbt] object FileEventMonitor {
  sealed trait Event[+T] {
    def entry: Entry[T]
    def occurredAt: Deadline
  }
  object Event {
    def unapply[T](event: Event[T]): Option[(Entry[T], Deadline)] =
      Some((event.entry, event.occurredAt))
  }

  final case class Creation[+T](override val entry: Entry[T],
                                override val occurredAt: Deadline = Deadline.now)
      extends Event[T] {
    override def equals(o: Any): Boolean = o match {
      case Creation(e, _) => this.entry == e
      case _              => false
    }
    override def hashCode(): Int = entry.hashCode()
  }

  final case class Update[+T](previous: Entry[T],
                              entry: Entry[T],
                              occurredAt: Deadline = Deadline.now)
      extends Event[T] {
    override def equals(o: Any): Boolean = o match {
      case Update(prev, e, _) =>
        this.previous == prev && this.entry == e
      case _ => false
    }
    override def hashCode(): Int = previous.hashCode() ^ entry.hashCode()
  }

  final case class Deletion[+T](entry: Entry[T], occurredAt: Deadline = Deadline.now)
      extends Event[T] {
    override def equals(o: Any): Boolean = o match {
      case Deletion(e, _) => this.entry == e
      case _              => false
    }
    override def hashCode(): Int = entry.hashCode()
  }

  private[sbt] def apply[T](observable: Observable[T],
                            logger: WatchLogger = NullWatchLogger): FileEventMonitor[T] =
    new FileEventMonitorImpl[T](observable, logger)

  /**
   * Create a [[FileEventMonitor]] that tracks recent events to prevent creating multiple events
   * for the same path within the same window. This exists because there are many programs that
   * may make a burst of modifications to a file in a short window. For example, many programs
   * implement save by renaming a buffer file to the target file. This can create both a deletion
   * and a creation event for the target file but we only want to create one file in this scenario.
   * This scenario is so common that we specifically handle it with the quarantinePeriod parameter.
   * When the monitor detects a file deletion, it does not actually produce an event for that
   * path until the quarantinePeriod has elapsed or a creation or update event is detected.
   *
   * @param observable the [[FileTreeDataView.Observable]] to monitor for events
   * @param period the anti-entropy quarantine period
   * @param logger a debug logger
   * @param quarantinePeriod configures how long we wait before creating an event for a delete file.
   * @param retentionPeriod configures how long in wall clock time to cache the anti-entropy
   *                        deadline for a path. This is needed because sometimes there are long
   *                        delays between polls and we do not want a stale event that occurred
   *                        within an anti-entropy window for the event path to trigger. This
   *                        is not a perfect solution, but a smarter solution would require
   *                        introspection of the internal state of the pending events.
   * @tparam T the generic type for the [[FileTreeDataView.Observable]] that we're monitoring
   * @return the [[FileEventMonitor]] instance.
   */
  private[sbt] def antiEntropy[T](observable: Observable[T],
                                  period: FiniteDuration,
                                  logger: WatchLogger,
                                  quarantinePeriod: FiniteDuration,
                                  retentionPeriod: FiniteDuration): FileEventMonitor[T] = {
    new AntiEntropyFileEventMonitor(period,
                                    new FileEventMonitorImpl[T](observable, logger),
                                    logger,
                                    quarantinePeriod,
                                    retentionPeriod)
  }

  /**
   * Create a [[FileEventMonitor]] that tracks recent events to prevent creating multiple events
   * for the same path within the same window. This exists because there are many programs that
   * may make a burst of modifications to a file in a short window. For example, many programs
   * implement save by renaming a buffer file to the target file. This can create both a deletion
   * and a creation event for the target file but we only want to create one file in this scenario.
   * This scenario is so common that we specifically handle it with the quarantinePeriod parameter.
   * When the monitor detects a file deletion, it does not actually produce an event for that
   * path until the quarantinePeriod has elapsed or a creation or update event is detected.
   *
   * @param monitor the [[FileEventMonitor]] instance to which we apply anti-entropy restrictions
   * @param period the anti-entropy quarantine period
   * @param logger a debug logger
   * @param quarantinePeriod configures how long we wait before creating an event for a delete file.
   * @param retentionPeriod configures how long in wall clock time to cache the anti-entropy
   *                        deadline for a path. This is needed because sometimes there are long
   *                        delays between polls and we do not want a stale event that occurred
   *                        within an anti-entropy window for the event path to trigger. This
   *                        is not a perfect solution, but a smarter solution would require
   *                        introspection of the internal state of the pending events.
   * @tparam T the generic type for the delegate [[FileEventMonitor]] that we're monitoring
   * @return the [[FileEventMonitor]] instance.
   */
  def antiEntropy[T](monitor: FileEventMonitor[T],
                     period: FiniteDuration,
                     logger: WatchLogger,
                     quarantinePeriod: FiniteDuration,
                     retentionPeriod: FiniteDuration): FileEventMonitor[T] = {
    new AntiEntropyFileEventMonitor(period, monitor, logger, quarantinePeriod, retentionPeriod)
  }

  private class FileEventMonitorImpl[T](observable: Observable[T], logger: WatchLogger)
      extends FileEventMonitor[T] {
    private case object Trigger
    private val events =
      new ConcurrentHashMap[JPath, FileEventMonitor.Event[T]]().asScala
    private val queue = new ArrayBlockingQueue[Trigger.type](1)
    private val lock = new Object
    /*
     * This method will coalesce the new event with a possibly existing previous event. The aim is
     * that whenever the user calls poll, they will get the final difference between the previous
     * state of the file system and the new state, but without the incremental changes that may
     * have occurred along the way.
     */
    private def add(event: Event[T]): Unit = {
      def put(path: JPath, event: FileEventMonitor.Event[T]): Unit = lock.synchronized {
        events.put(path, event)
        queue.offer(Trigger)
        ()
      }
      logger.debug(s"Received $event")
      val path = event.entry.typedPath.toPath
      lock.synchronized(events.putIfAbsent(path, event)) match {
        case Some(d: Deletion[T]) =>
          event match {
            case _: Deletion[T] =>
            case Update(previous, _, _) =>
              put(path, Deletion(previous, event.occurredAt))
            case _ => put(path, Update(d.entry, event.entry, event.occurredAt))
          }
        case Some(_: Creation[T]) =>
          event match {
            case _: Deletion[T] => events.remove(path)
            case _: Update[T]   => put(path, new Creation(event.entry, event.occurredAt))
            case _              => put(path, event)
          }
        case Some(Update(previous, _, ts)) =>
          event match {
            case _: Deletion[T] => put(path, Deletion(previous, ts))
            case e              => put(path, Update(previous, e.entry, ts))
          }
        case None =>
          lock.synchronized(queue.offer(Trigger))
      }
      ()
    }
    private val handle = observable.addObserver(
      Observer(
        (entry: Entry[T]) => add(Creation(entry)),
        (entry: Entry[T]) => add(Deletion(entry)),
        (previous: Entry[T], current: Entry[T]) => add(Update(previous, current))
      ))

    @tailrec
    final override def poll(duration: Duration): Seq[FileEventMonitor.Event[T]] = {
      val start = Deadline.now
      if (lock.synchronized(events.isEmpty) && duration > 0.seconds) {
        duration match {
          case d: FiniteDuration => queue.poll(d.toNanos, TimeUnit.NANOSECONDS)
          case _                 => queue.take()
        }
      }
      val res = lock.synchronized {
        queue.poll(0, TimeUnit.MILLISECONDS)
        val r = events.values.toVector
        events.clear()
        r
      }
      res match {
        case e if e.isEmpty =>
          val now = Deadline.now
          duration match {
            case d: FiniteDuration => if (now < start + d) poll((start + d) - now) else Nil
            case _                 => poll(duration)
          }
        case e => e
      }
    }

    override def close(): Unit = {
      observable.removeObserver(handle)
      events.clear()
      observable.close()
    }
  }
  private class AntiEntropyFileEventMonitor[T](period: FiniteDuration,
                                               fileEventMonitor: FileEventMonitor[T],
                                               logger: WatchLogger,
                                               quarantinePeriod: FiniteDuration,
                                               retentionPeriod: FiniteDuration)
      extends FileEventMonitor[T] {
    private[this] val antiEntropyDeadlines = new ConcurrentHashMap[JPath, Deadline].asScala
    /*
     * It is very common for file writes to be implemented as a move, which manifests as a delete
     * followed by a write. In sbt, this can manifest as continuous builds triggering for the delete
     * and re-compiling before the replaced file is present. This is undesirable because deleting
     * the file can break the build. Even if it doesn't break the build, it may cause the build to
     * become inconsistent with the file system if the creation is dropped due to anti-entropy. To
     * avoid this behavior, we quarantine the deletion and return immediately if a subsequent
     * creation is detected. This provides a reasonable compromise between low latency and
     * correctness.
     */
    private[this] val quarantinedEvents = new ConcurrentHashMap[JPath, Event[T]].asScala
    @tailrec
    override final def poll(duration: Duration): Seq[Event[T]] = {
      val start = Deadline.now
      /*
       * The impl is tail recursive to handle the case when we quarantine a deleted file or find
       * an event for a path that is an anti-entropy quarantine. In these cases, if there are other
       * events in the queue, we want to immediately pull them. Otherwise it's possible to return
       * None while there events ready in the queue.
       */
      val results = fileEventMonitor.poll(duration)
      /*
       * Note that this transformation is not purely functional because it has the side effect of
       * modifying the quarantinedEvents and antiEntropyDeadlines maps.
       */
      val transformed = results.flatMap {
        case event @ Event(entry @ Entry(typedPath, _), occurredAt) =>
          val path = typedPath.toPath
          val quarantined = if (typedPath.exists) quarantinedEvents.remove(path) else None
          quarantined match {
            case Some(Deletion(deletedEntry, deletionTs)) =>
              antiEntropyDeadlines.put(path, deletionTs + period)
              logger.debug(
                s"Triggering event for newly created path $path that was previously quarantined.")
              Some(Update(deletedEntry, entry, deletionTs))
            case _ =>
              antiEntropyDeadlines.get(path) match {
                case Some(deadline) if occurredAt <= deadline =>
                  val msg = s"Discarding entry for recently updated path $path. " +
                    s"This event occurred ${(occurredAt - (deadline - period)).toMillis} ms since " +
                    "the last event for this path."
                  logger.debug(msg)
                  None
                case _ if !typedPath.exists =>
                  quarantinedEvents.put(path, event)
                  logger.debug(s"Quarantining deletion event for path $path for $period")
                  None
                case _ =>
                  antiEntropyDeadlines.put(path, occurredAt + period)
                  logger.debug(s"Received event for path $path")
                  Some(event)
              }
          }
      } ++ quarantinedEvents.collect {
        case (path, event @ Deletion(_, deadline)) if (deadline + quarantinePeriod).isOverdue =>
          quarantinedEvents.remove(path)
          antiEntropyDeadlines.put(path, deadline + period)
          logger.debug(s"Triggering event for previously quarantined deleted file: $path")
          event
      }
      // Keep old anti entropy events around for a while in case there are still unhandled
      // events that occurred between polls. This is necessary because there is no background
      // thread polling the events. Because the period between polls could be quite large, it's
      // possible that there are unhandled events that actually occurred within the anti-entropy
      // window for the path. By setting a long retention time, we try to avoid this.
      antiEntropyDeadlines.retain((_, deadline) => !(deadline + retentionPeriod).isOverdue)
      transformed match {
        case s if s.nonEmpty => s
        case _ =>
          val limit = duration - (Deadline.now - start)
          if (limit > 0.millis) poll(limit) else Nil
      }
    }

    override def close(): Unit = {
      quarantinedEvents.clear()
      antiEntropyDeadlines.clear()
      fileEventMonitor.close()
    }
  }
}
