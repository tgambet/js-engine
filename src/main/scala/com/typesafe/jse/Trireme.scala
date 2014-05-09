package com.typesafe.jse

import akka.actor._
import scala.concurrent.blocking
import java.io._
import akka.contrib.process.StreamEvents.Ack
import akka.contrib.process._
import scala.collection.immutable
import io.apigee.trireme.core._
import scala.collection.JavaConverters._
import org.mozilla.javascript.RhinoException
import scala.util.Try
import io.apigee.trireme.core.internal.{NoCloseOutputStream, NoCloseInputStream}
import scala.concurrent.duration._
import com.typesafe.jse.Engine.ExecuteJs

/**
 * Declares an in-JVM Rhino based JavaScript engine supporting the Node API.
 * The <a href="https://github.com/apigee/trireme#trireme">Trireme</a> project provides this capability.
 * The actor is expected to be associated with a blocking dispatcher as its use of Jdk streams are blocking.
 */
class Trireme(
               stdArgs: immutable.Seq[String],
               stdEnvironment: Map[String, String],
               ioDispatcherId: String
               ) extends Engine(stdArgs, stdEnvironment) {

  // The main objective of this actor implementation is to establish actors for both the execution of
  // Trireme code (Trireme's execution is blocking), and actors for the source of stdio (which is also blocking).
  // This actor is then a conduit of the IO as a result of execution.

  val StdioTimeout = 30.seconds

  def receive = {
    case ExecuteJs(source, args, timeout, timeoutExitValue, environment) =>
      val requester = sender()

      val stdinSink = context.actorOf(BufferingSink.props(ioDispatcherId = ioDispatcherId), "stdin")
      val stdinIs = new SourceStream(stdinSink, StdioTimeout)
      val stdoutSource = context.actorOf(ForwardingSource.props(self, ioDispatcherId = ioDispatcherId), "stdout")
      val stdoutOs = new SinkStream(stdoutSource, StdioTimeout)
      val stderrSource = context.actorOf(ForwardingSource.props(self, ioDispatcherId = ioDispatcherId), "stderr")
      val stderrOs = new SinkStream(stderrSource, StdioTimeout)

      try {
        context.become(engineIOHandler(
          stdinSink, stdoutSource, stderrSource,
          requester,
          Ack,
          timeout, timeoutExitValue
        ))

        context.actorOf(TriremeShell.props(
          source.getCanonicalFile,
          stdArgs ++ args,
          stdEnvironment ++ environment,
          stdinIs, stdoutOs, stderrOs
        ), "trireme-shell") ! TriremeShell.Execute

      } finally {
        // We don't need stdin
        blocking(Try(stdinIs.close()))
      }
  }
}

object Trireme {
  /**
   * Give me a Trireme props.
   */
  def props(
             stdArgs: immutable.Seq[String] = Nil,
             stdEnvironment: Map[String, String] = Map.empty,
             ioDispatcherId: String = "blocking-process-io-dispatcher"
             ): Props = {
    Props(classOf[Trireme], stdArgs, stdEnvironment, ioDispatcherId)
      .withDispatcher(ioDispatcherId)
  }

}


/**
 * Manage the execution of the Trireme shell setting up its environment, running the main entry point
 * and sending its parent the exit code when we're done.
 */
private[jse] class TriremeShell(
                                 source: File,
                                 args: immutable.Seq[String],
                                 environment: Map[String, String],
                                 stdinIs: InputStream,
                                 stdoutOs: OutputStream,
                                 stderrOs: OutputStream
                                 ) extends Actor with ActorLogging {

  import TriremeShell._

  val env = (sys.env ++ environment).asJava
  val nodeEnv = new NodeEnvironment()
  val sandbox = new Sandbox()
  sandbox.setStdin(new NoCloseInputStream(stdinIs))
  sandbox.setStdout(new NoCloseOutputStream(stdoutOs))
  sandbox.setStderr(new NoCloseOutputStream(stderrOs))

  def receive = {
    case Execute =>

      if (log.isDebugEnabled) {
        log.debug("Invoking Trireme with {}", args)
      }

      val script = nodeEnv.createScript(source.getName, source, args.toArray)
      script.setSandbox(sandbox)
      script.setEnvironment(env)

      val senderSel = sender().path
      val senderSys = context.system
      script.execute.setListener(new ScriptStatusListener {
        def onComplete(script: NodeScript, status: ScriptStatus): Unit = {
          if (status.hasCause) {
            try {
              status.getCause match {
                case e: RhinoException =>
                  stderrOs.write(e.getLocalizedMessage.getBytes("UTF-8"))
                  stderrOs.write(e.getScriptStackTrace.getBytes("UTF-8"))
                case t =>
                  t.printStackTrace(new PrintStream(stderrOs))
              }
            } catch {
              case e: RuntimeException =>
                log.error("Problem completing Trireme. Throwing exception, meanwhile here's the Trireme problem", status.getCause)
                throw e
            }
          }
          stdoutOs.close()
          stderrOs.close()
          senderSys.actorSelection(senderSel) ! status.getExitCode
        }
      })
  }

}

private[jse] object TriremeShell {
  def props(
             moduleBase: File,
             args: immutable.Seq[String],
             environment: Map[String, String],
             stdinIs: InputStream,
             stdoutOs: OutputStream,
             stderrOs: OutputStream
             ): Props = {
    Props(classOf[TriremeShell], moduleBase, args, environment, stdinIs, stdoutOs, stderrOs)
  }

  case object Execute

}