package com.typesafe.jse

import akka.actor._
import org.mozilla.javascript.tools.shell.Main
import scala.collection.mutable.ListBuffer
import org.mozilla.javascript._
import scala.concurrent.blocking
import java.io._
import akka.contrib.process.StreamEvents.Ack
import akka.contrib.process._
import scala.collection.immutable
import scala.concurrent.duration._
import com.typesafe.jse.Engine.ExecuteJs
import scala.util.Try

/**
 * Declares an in-JVM Rhino based JavaScript engine. The actor is expected to be
 * associated with a blocking dispatcher as calls to Rhino and its use of Jdk streams
 * are blocking.
 */
class Rhino(
             stdArgs: immutable.Seq[String],
             stdModulePaths: immutable.Seq[String],
             rhinoShellDispatcherId: String,
             ioDispatcherId: String
             ) extends Engine(stdArgs, Map.empty) {

  // The main objective of this actor implementation is to establish actors for both the execution of
  // Rhino code (Rhino's execution is blocking), and actors for the source of stdio (which is also blocking).
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

        context.actorOf(RhinoShell.props(
          source,
          stdArgs ++ args,
          stdModulePaths,
          stdinIs, stdoutOs, stderrOs,
          rhinoShellDispatcherId
        ), "rhino-shell") ! RhinoShell.Execute

      } finally {
        // We don't need stdin
        blocking(Try(stdinIs.close()))
      }
  }
}

object Rhino {
  /**
   * Give me a Rhino props.
   */
  def props(
             stdArgs: immutable.Seq[String] = Nil,
             stdModulePaths: immutable.Seq[String] = Nil,
             rhinoShellDispatcherId: String = "rhino-shell-dispatcher",
             ioDispatcherId: String = "blocking-process-io-dispatcher"
             ): Props = {
    Props(classOf[Rhino], stdArgs, stdModulePaths, rhinoShellDispatcherId, ioDispatcherId)
      .withDispatcher(ioDispatcherId)
  }

}


/**
 * Manage the execution of the Rhino shell setting up its environment, running the main entry point
 * and sending its parent the exit code when it can see that the stdio sources have closed.
 */
private[jse] class RhinoShell(
                               source: File,
                               args: immutable.Seq[String],
                               modulePaths: immutable.Seq[String],
                               stdinIs: InputStream,
                               stdoutOs: OutputStream,
                               stderrOs: OutputStream
                               ) extends Actor with ActorLogging {

  import RhinoShell._

  // Formulate arguments to the Rhino shell.
  val lb = ListBuffer[String]()
  lb ++= Seq(
    "-opt", "-1",
    "-modules", source.getParent
  )
  lb ++= modulePaths.flatMap(Seq("-modules", _))
  lb += source.getCanonicalPath
  lb ++= args

  val shellArgs = lb.toArray

  def receive = {
    case Execute =>
      // Each time we use Rhino we set properties in the global scope that represent the stdout and stderr
      // output streams. These output streams are plugged into our source actors.
      withContext {
        def jsStdoutOs = Context.javaToJS(stdoutOs, Main.getGlobal)
        ScriptableObject.putProperty(Main.getGlobal, "stdout", jsStdoutOs)
        def jsStderrOs = Context.javaToJS(stderrOs, Main.getGlobal)
        ScriptableObject.putProperty(Main.getGlobal, "stderr", jsStderrOs)
      }
      val exitCode = blocking {
        try {
          if (log.isDebugEnabled) {
            log.debug("Invoking Rhino with {}", shellArgs)
          }
          Main.exec(shellArgs)
        } finally {
          stdoutOs.close()
          stderrOs.close()
        }
      }

      sender() ! exitCode
  }

}

private[jse] object RhinoShell {
  def props(
             moduleBase: File,
             args: immutable.Seq[String],
             modulePaths: immutable.Seq[String],
             stdinIs: InputStream,
             stdoutOs: OutputStream,
             stderrOs: OutputStream,
             rhinoShellDispatcherId: String
             ): Props = {
    Props(classOf[RhinoShell], moduleBase, args, modulePaths, stdinIs, stdoutOs, stderrOs)
      .withDispatcher(rhinoShellDispatcherId)
  }

  case object Execute

  private val lineSeparator = System.getProperty("line.separator").getBytes("UTF-8")

  /*
   * Our override of Rhino's print function. Output is sent to the stdout source. There is no provision in Rhino's
   * shell to send to stderr.
   *
   * Has to be public in order for the Rhino shell to find it.
   */
  def print(
             cx: Context,
             thisObj: Scriptable,
             args: Array[Any],
             funObj: org.mozilla.javascript.Function
             ): Any = {
    val property = funObj.getParentScope.get("stdout", thisObj)
    property match {
      case jsOs: NativeJavaObject =>
        jsOs.unwrap() match {
          case os: OutputStream =>
            args.foreach {
              arg =>
                val s = Context.toString(arg)
                os.write(s.getBytes("UTF-8"))
            }
            os.write(lineSeparator)
        }
    }
    Context.getUndefinedValue
  }

  // A utility to safely manage Rhino contexts.
  private def withContext(block: => Unit): Unit = {
    Context.enter()
    try block finally {
      Context.exit()
    }
  }

  // Initialise our Rhino environment. If we've never done so before then do general Rhino shell
  // initialisation and then override its print function. The Rhino shell is all static so this
  // only need be done once.
  if (!Main.getGlobal.isInitialized) {
    Main.getGlobal.init(Main.shellContextFactory)
  }

  withContext {
    Main.getGlobal.defineFunctionProperties(Array("print"), classOf[RhinoShell], ScriptableObject.DONTENUM)
  }

}