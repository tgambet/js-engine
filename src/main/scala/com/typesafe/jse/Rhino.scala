package com.typesafe.jse

import akka.actor._
import akka.contrib.process._
import akka.contrib.process.StreamEvents.Ack
import java.io._
import java.net.URI
import scala.collection.immutable
import scala.concurrent.blocking
import scala.concurrent.duration._
import scala.util.Try

import org.mozilla.javascript._
import org.mozilla.javascript.commonjs.module.RequireBuilder
import org.mozilla.javascript.commonjs.module.provider.{UrlModuleSourceProvider, SoftCachingModuleScriptProvider}
import org.mozilla.javascript.tools.shell.Global

import com.typesafe.jse.Engine.ExecuteJs

/**
 * Declares an in-JVM Rhino based JavaScript engine. The actor is expected to be
 * associated with a blocking dispatcher as calls to Rhino and its use of Jdk streams
 * are blocking.
 */
class Rhino(
             stdArgs: immutable.Seq[String],
             stdModulePaths: immutable.Seq[String],
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
          source.getCanonicalFile,
          stdArgs ++ args,
          stdModulePaths,
          stdinIs, stdoutOs, stderrOs
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
             ioDispatcherId: String = "blocking-process-io-dispatcher"
             ): Props = {
    Props(classOf[Rhino], stdArgs, stdModulePaths, ioDispatcherId)
      .withDispatcher(ioDispatcherId)
  }

}


/**
 * Manage the execution of the Rhino shell setting up its environment, running the main entry point
 * and sending its parent the exit code when it can see that the stdio sources have closed.
 */
private[jse] class RhinoShell(
                               script: File,
                               args: immutable.Seq[String],
                               modulePaths: immutable.Seq[String],
                               stdinIs: InputStream,
                               stdoutOs: OutputStream,
                               stderrOs: OutputStream
                               ) extends Actor with ActorLogging {

  import RhinoShell._

  // Some doc to help understanding this code
  // https://groups.google.com/d/msg/envjs/Tnvpvvzu_9Q/F-g9MoJ8nNgJ
  // https://groups.google.com/forum/#!msg/mozilla-rhino/HCMh_lAKiI4/P1MA3sFsNKQJ
  // http://stackoverflow.com/questions/11080037/java-7-rhino-1-7r3-support-for-commonjs-modules (on why we can't use javax API)

  val requireBuilder = {
    import scala.collection.JavaConversions
    val paths = script.getParentFile.toURI +: modulePaths.map(new URI(_))
    val sourceProvider = new UrlModuleSourceProvider(JavaConversions.asJavaIterable(paths), null)
    val scriptProvider = new SoftCachingModuleScriptProvider(sourceProvider)
    new RequireBuilder().setModuleScriptProvider(scriptProvider)
  }

  def receive = {

    case Execute =>

      val ctx = Context.enter()

      try {

        // Create a global object so that we have Rhino shell functions in scope (e.g. load, print, ...)
        val global = {
          val g = new Global()
          g.init(ctx)
          g.setIn(stdinIs)
          g.setErr(new PrintStream(stderrOs))
          g.setOut(new PrintStream(stdoutOs))
          g
        }

        // Prepare a scope by passing the arguments and adding CommonJS support
        val scope = {
          val s = ctx.initStandardObjects(global, false)
          s.defineProperty("arguments", args.toArray, ScriptableObject.READONLY)
          val require = requireBuilder.createRequire(ctx, s)
          require.install(s)
          s
        }

        // Evaluate
        val reader = new FileReader(script)
        blocking(ctx.evaluateReader(scope, reader, script.getName, 0, null))
        sender() ! 0

      } catch {

        case e: RhinoException =>
          stderrOs.write(e.getLocalizedMessage.getBytes("UTF-8"))
          stderrOs.write(e.getScriptStackTrace.getBytes("UTF-8"))
          sender() ! 1

        case t: Exception =>
          t.printStackTrace(new PrintStream(stderrOs))
          sender() ! 1

      } finally {

        Try(stdoutOs.close())
        Try(stderrOs.close())
        Context.exit()
      }

  }

}

private[jse] object RhinoShell {
  def props(
             moduleBase: File,
             args: immutable.Seq[String],
             modulePaths: immutable.Seq[String],
             stdinIs: InputStream,
             stdoutOs: OutputStream,
             stderrOs: OutputStream
             ): Props = {
    Props(classOf[RhinoShell], moduleBase, args, modulePaths, stdinIs, stdoutOs, stderrOs)
  }

  case object Execute

}
