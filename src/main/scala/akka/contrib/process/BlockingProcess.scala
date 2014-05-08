package akka.contrib.process

import akka.actor._
import akka.util.{Timeout, ByteString}
import java.io._
import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.concurrent.{Await, blocking}
import java.lang.{ProcessBuilder => JdkProcessBuilder}
import akka.contrib.process.BlockingProcess.Started
import akka.contrib.process.StreamEvents.{Read, Done, Ack}
import akka.pattern.ask
import scala.concurrent.duration.{FiniteDuration, Duration}
import scala.annotation.tailrec
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process encapsulates an operating system process and its ability to be communicated with
 * via stdio i.e. stdin, stdout and stderr. The sink for stdin and the sources for stdout
 * and stderr are communicated in a Started event upon the actor being established. The
 * receiving actor (passed in as a constructor arg) is then subsequently sent stdout and
 * stderr events. When there are no more stdout or stderr events then the process's exit
 * code is communicated to the receiver as an int value. The exit code will always be
 * the last event communicated by the process unless the process is a detached one.
 *
 * The actor is expected to be associated with a blocking dispatcher as various calls are made
 * to input and output streams which can block.
 */
class BlockingProcess(args: immutable.Seq[String], environment: Map[String, String], receiver: ActorRef, detached: Boolean)
  extends Actor {

  // This quoting functionality is as recommended per http://bugs.java.com/view_bug.do?bug_id=6511002
  // The JDK can't change due to its backward compatibility requirements, but we have no such constraint
  // here. Args should be able to be expressed consistently by the user of our API no matter whether
  // execution is on Windows or not.

  def needsQuoting(s: String): Boolean =
    if (s.isEmpty) true else s.exists(c => c == ' ' || c == '\t' || c == '\\' || c == '"')

  def winQuote(s: String): String = {
    if (!needsQuoting(s)) {
      s
    } else {
      "\"" + s.replaceAll("([\\\\]*)\"", "$1$1\\\\\"").replaceAll("([\\\\]*)\\z", "$1$1") + "\""
    }
  }

  val isWindows: Boolean = System.getProperty("os.name").toLowerCase.contains("win")

  def prepareArgs(args: immutable.Seq[String]): immutable.Seq[String] =
    if (isWindows) args.map(winQuote) else args

  val pb = new JdkProcessBuilder(prepareArgs(args).asJava)
  pb.environment().putAll(environment.asJava)
  val p = pb.start()

  val stdinSink = context.actorOf(OutputStreamSink.props(p.getOutputStream), "stdin")
  val stdoutSource = context.watch(context.actorOf(InputStreamSource.props(p.getInputStream, receiver), "stdout"))
  val stderrSource = context.watch(context.actorOf(InputStreamSource.props(p.getErrorStream, receiver), "stderr"))

  var openStreams = 2

  def receive = {
    case Terminated(`stdoutSource` | `stderrSource`) =>
      openStreams -= 1
      if (openStreams == 0 && !detached) {
        val exitValue = blocking {
          p.waitFor()
          p.exitValue()
        }
        receiver ! exitValue
        context.stop(self)
      }
  }

  override def postStop() {
    if (!detached) p.destroy()
  }

  override def preStart() {
    receiver ! Started(stdinSink, stdoutSource, stderrSource)
  }
}

object BlockingProcess {

  /**
   * Return the props required to create a process actor.
   * @param args The sequence of string arguments to pass to the process.
   * @param receiver The actor to receive output and error events.
   * @param detached Whether the process will be daemonic.
   * @param ioDispatcherId The name given to the dispatcher configuration that will be used to manage blocking IO.
   * @return a props object that can be used to create the process actor.
   */
  def props(
             args: immutable.Seq[String],
             environment: Map[String, String],
             receiver: ActorRef,
             detached: Boolean = false,
             ioDispatcherId: String = "blocking-process-io-dispatcher"
             ): Props = Props(classOf[BlockingProcess], args, environment, receiver, detached)
    .withDispatcher(ioDispatcherId)

  /**
   * Sent on startup to the receiver - specifies the actors used for managing input, output and
   * error respectively.
   */
  case class Started(stdinSink: ActorRef, stdoutSource: ActorRef, stderrSource: ActorRef)

}


/**
 * Declares the types of event that are involved with streaming.
 */
object StreamEvents {

  /**
   * Sent in response to an Output even.
   */
  case object Ack

  /**
   * Sent when no more Output events are expected.
   */
  case object Done

  /**
   * Read n bytes from an input.
   */
  case class Read(size: Int)

}

/**
 * A target to stream bytes to. Flow control is provided i.e. every message of bytes sent is acknowledged with an
 * Ack. A Done event is expected when there is no more data to be written.
 */
abstract class Sink extends Actor

/**
 * A sink of data given an output stream.
 */
class OutputStreamSink(os: OutputStream) extends Sink {
  def receive = {
    case bytes: ByteString =>
      blocking {
        os.write(bytes.toArray)
      }
      sender() ! Ack
    case Done => context.stop(self)
  }

  override def postStop() {
    os.close()
  }
}

object OutputStreamSink {
  def props(
             os: OutputStream,
             ioDispatcherId: String = "blocking-process-io-dispatcher"
             ): Props = Props(classOf[OutputStreamSink], os)
    .withDispatcher(ioDispatcherId)
}

/**
 * A buffering sink. The present implementation is quite limited in that the buffer can grow indefinitely.
 */
class BufferingSink extends Sink {
  var buffer = ByteString()

  def receive = {
    case bytes: ByteString =>
      buffer = buffer.concat(bytes)
      sender() ! Ack
    case Read(size) =>
      val (readBytes, remainingBytes) = buffer.splitAt(size)
      buffer = remainingBytes
      sender() ! readBytes
    case Done => context.stop(self)
  }
}

object BufferingSink {
  def props(
             ioDispatcherId: String = "blocking-process-io-dispatcher"
             ): Props = Props(classOf[BufferingSink])
    .withDispatcher(ioDispatcherId)
}


/**
 * A holder of data received and forwarded on to a receiver with flow control. There is only one sender expected and
 * that sender should not send again until an Ack from the previous send.
 * @param receiver the receiver of data from the source.
 */
abstract class Source(receiver: ActorRef) extends Actor

/**
 * A source of data given an input stream. Flow control is implemented and for each ByteString event received by the receiver,
 * an Ack is expected in return. At the end of the source, a Done event will be sent to the receiver and its associated
 * input stream is closed.
 */
class InputStreamSource(is: InputStream, receiver: ActorRef, pipeSize: Int) extends Source(receiver) {
  require(pipeSize > 0)

  val buffer = new Array[Byte](pipeSize)

  def receive = {
    case Ack =>
      val len = blocking {
        is.read(buffer)
      }
      if (len > -1) {
        receiver ! ByteString.fromArray(buffer, 0, len)
      } else {
        receiver ! Done
        context.stop(self)
      }
  }

  override def postStop() {
    is.close()
  }

  override def preStart() {
    self ! Ack // Start reading
  }
}

object InputStreamSource {
  def props(
             is: InputStream,
             receiver: ActorRef,
             pipeSize: Int = 1024,
             ioDispatcherId: String = "blocking-process-io-dispatcher"
             ): Props = Props(classOf[InputStreamSource], is, receiver, pipeSize)
    .withDispatcher(ioDispatcherId)
}

/**
 * A source of data that simply forwards on to the receiver.
 */
class ForwardingSource(receiver: ActorRef) extends Source(receiver) {
  def receive = {
    case bytes: ByteString =>
      val origSender = sender()
      receiver ! bytes
      context.become {
        case Ack =>
          origSender ! Ack
          context.unbecome()
        case Done => context.stop(self)
      }
    case Done => context.stop(self)
  }
}

object ForwardingSource {
  def props(
             receiver: ActorRef,
             ioDispatcherId: String = "blocking-process-io-dispatcher"
             ): Props = Props(classOf[ForwardingSource], receiver)
    .withDispatcher(ioDispatcherId)
}


/**
 * Forwards messages on to a Source in a blocking manner conforming to the JDK OutputStream.
 */
class SinkStream(val source: ActorRef, timeout: FiniteDuration) extends OutputStream {
  implicit val akkaTimeout = new Timeout(timeout)

  var isClosed = new AtomicBoolean(false)

  override def close(): Unit = if (isClosed.compareAndSet(false, true)) source ! Done

  override def write(b: Int): Unit = {
    try {
      Await.result(source ? ByteString(b), timeout)
    } catch {
      case e: RuntimeException =>
        isClosed.set(true)
        throw new IOException("While writing source stream", e)
    }
  }

  override def write(bytes: Array[Byte]): Unit = {
    try {
      Await.result(source ? ByteString.fromArray(bytes), timeout)
    } catch {
      case e: RuntimeException =>
        isClosed.set(true)
        throw new IOException("While writing to the source. Closing stream.", e)
    }
  }
}

/**
 * Reads from a sink in a blocking manner conforming to the JDK InputStream
 */
class SourceStream(val sink: ActorRef, timeout: FiniteDuration) extends InputStream {
  implicit val akkaTimeout = new Timeout(timeout)

  var isClosed = new AtomicBoolean(false)

  override def close(): Unit = if (isClosed.compareAndSet(false, true)) sink ! Done

  private def getBytes(size: Int): ByteString = {
    try {
      val bytes = Await.result((sink ? Read).mapTo[ByteString], timeout: Duration)
      sink ! Ack
      bytes
    } catch {
      case e: RuntimeException =>
        isClosed.set(true)
        throw new IOException("Problem when reading bytes from the sink. Closing stream.", e)
    }
  }

  @tailrec
  override final def read(): Int = {
    val bs = getBytes(1)
    if (!bs.isEmpty) {
      bs(0)
    } else {
      Thread.sleep(100)
      read()
    }
  }

  @tailrec
  override final def read(bytes: Array[Byte]): Int = {
    val bs = getBytes(bytes.size)
    if (!bs.isEmpty) {
      bs.copyToArray(bytes, 0, bs.size)
      bs.size
    } else {
      Thread.sleep(100)
      read(bytes)
    }
  }
}