/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2011, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scala.concurrent.impl



import java.util.concurrent.TimeUnit.{ NANOSECONDS, MILLISECONDS }
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import scala.concurrent.{Awaitable, ExecutionContext, resolveEither, resolver, blocking, CanAwait, TimeoutException}
//import scala.util.continuations._
import scala.concurrent.util.Duration
import scala.util
import scala.annotation.tailrec
//import scala.concurrent.NonDeterministic



private[concurrent] trait Promise[T] extends scala.concurrent.Promise[T] with Future[T] {
  def future: scala.concurrent.Future[T] = this
}


object Promise {
  /** Default promise implementation.
   */
  class DefaultPromise[T](implicit val executor: ExecutionContext) extends AbstractPromise with Promise[T] { self =>
    updater.set(this, Nil) // Start at "No callbacks" //FIXME switch to Unsafe instead of ARFU

    def newPromise[S]: scala.concurrent.Promise[S] = new Promise.DefaultPromise()

    protected final def tryAwait(atMost: Duration): Boolean = {
      @tailrec
      def awaitUnsafe(waitTimeNanos: Long): Boolean = {
        if (value.isEmpty && waitTimeNanos > 0) {
          val ms = NANOSECONDS.toMillis(waitTimeNanos)
          val ns = (waitTimeNanos % 1000000l).toInt // as per object.wait spec
          val start = System.nanoTime()
          try {
            synchronized {
              while (!isCompleted) wait(ms, ns)
            }
          } catch {
            case e: InterruptedException =>
          }

          awaitUnsafe(waitTimeNanos - (System.nanoTime() - start))
        } else
          isCompleted
      }
      //FIXME do not do this if there'll be no waiting
      blocking(Future.body2awaitable(awaitUnsafe(if (atMost.isFinite) atMost.toNanos else Long.MaxValue)), atMost)
    }

    @throws(classOf[TimeoutException])
    def ready(atMost: Duration)(implicit permit: CanAwait): this.type =
      if (isCompleted || tryAwait(atMost)) this
      else throw new TimeoutException("Futures timed out after [" + atMost.toMillis + "] milliseconds")

    @throws(classOf[Exception])
    def result(atMost: Duration)(implicit permit: CanAwait): T =
      ready(atMost).value.get match {
        case Left(e)  => throw e
        case Right(r) => r
      }

    def value: Option[Either[Throwable, T]] = getState match {
      case c: Either[_, _] => Some(c.asInstanceOf[Either[Throwable, T]])
      case _               => None
    }

    override def isCompleted(): Boolean = getState match { // Cheaper than boxing result into Option due to "def value"
      case _: Either[_, _] => true
      case _               => false
    }

    @inline
    private[this] final def updater = AbstractPromise.updater.asInstanceOf[AtomicReferenceFieldUpdater[AbstractPromise, AnyRef]]

    @inline
    protected final def updateState(oldState: AnyRef, newState: AnyRef): Boolean = updater.compareAndSet(this, oldState, newState)

    @inline
    protected final def getState: AnyRef = updater.get(this)

    def tryComplete(value: Either[Throwable, T]): Boolean = {
      (try {
        @tailrec
        def tryComplete(v: Either[Throwable, T]): List[Either[Throwable, T] => Unit] = {
          getState match {
            case raw: List[_] =>
              val cur = raw.asInstanceOf[List[Either[Throwable, T] => Unit]]
              if (updateState(cur, v)) cur else tryComplete(v)
            case _ => null
          }
        }
        tryComplete(resolveEither(value))
      } finally {
        synchronized { notifyAll() } //Notify any evil blockers
      }) match {
        case null             => false
        case cs if cs.isEmpty => true
        case cs               => Future.dispatchFuture(executor, () => cs.foreach(f => notifyCompleted(f, value))); true
      }
    }

    def onComplete[U](func: Either[Throwable, T] => U): this.type = {
      @tailrec //Returns null if callback was added and the result if the promise was already completed
      def tryAddCallback(): Either[Throwable, T] =
        getState match {
          case r: Either[_, _]    => r.asInstanceOf[Either[Throwable, T]]
          case listeners: List[_] => if (updateState(listeners, func :: listeners)) null else tryAddCallback()
        }


      tryAddCallback() match {
        case null => this
        case completed =>
          Future.dispatchFuture(executor, () => notifyCompleted(func, completed))
          this
      }
    }

    private final def notifyCompleted(func: Either[Throwable, T] => Any, result: Either[Throwable, T]) {
      try {
        func(result)
      } catch {
        case NonFatal(e) => executor reportFailure e
      }
    }
  }

  /** An already completed Future is given its result at creation.
   *
   *  Useful in Future-composition when a value to contribute is already available.
   */
  final class KeptPromise[T](suppliedValue: Either[Throwable, T])(implicit val executor: ExecutionContext) extends Promise[T] {

    val value = Some(resolveEither(suppliedValue))

    override def isCompleted(): Boolean = true

    def newPromise[S]: scala.concurrent.Promise[S] = new Promise.DefaultPromise()

    def tryComplete(value: Either[Throwable, T]): Boolean = false

    def onComplete[U](func: Either[Throwable, T] => U): this.type = {
      val completedAs = value.get
      Future.dispatchFuture(executor, () => func(completedAs))
      this
    }

    def ready(atMost: Duration)(implicit permit: CanAwait): this.type = this

    def result(atMost: Duration)(implicit permit: CanAwait): T = value.get match {
      case Left(e)  => throw e
      case Right(r) => r
    }
  }

}
