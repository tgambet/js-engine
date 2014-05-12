package com.typesafe.jse

import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import org.specs2.mutable.Specification
import com.typesafe.jse.Engine.JsExecutionResult
import java.io.File
import scala.collection.immutable
import akka.pattern.ask
import akka.util.Timeout
import akka.actor.{ActorRef, ActorSystem}
import scala.concurrent.Await
import java.util.concurrent.TimeUnit

@RunWith(classOf[JUnitRunner])
class RhinoSpec extends Specification {

  //sequential

  def withEngine[T](block: ActorRef => T): T = {
    val system = ActorSystem()
    val engine = system.actorOf(Rhino.props(), "engine")
    try {
      block(engine)
    } finally {
      system.shutdown()
      system.awaitTermination()
    }
  }

  "The Rhino engine" should {

    "execute some javascript by passing in a string arg and comparing its return value" in {
      withEngine {
        engine =>
          val f = new File(classOf[RhinoSpec].getResource("test-rhino.js").toURI)
          implicit val timeout = Timeout(5000L, TimeUnit.MILLISECONDS)

          val futureResult = (engine ? Engine.ExecuteJs(f, immutable.Seq("999"), timeout.duration)).mapTo[JsExecutionResult]
          val result = Await.result(futureResult, timeout.duration)

          new String(result.error.toArray, "UTF-8").trim mustEqual ""
          new String(result.output.toArray, "UTF-8").trim mustEqual "Hello 999"
      }
    }

    "execute some javascript by passing in a string arg and comparing its return value expecting an error" in {
      withEngine {
        engine =>
          val f = new File(classOf[RhinoSpec].getResource("test-node.js").toURI)
          implicit val timeout = Timeout(5000L, TimeUnit.MILLISECONDS)

          val futureResult = (engine ? Engine.ExecuteJs(f, immutable.Seq("888"), timeout.duration)).mapTo[JsExecutionResult]
          val result = Await.result(futureResult, timeout.duration)

          new String(result.output.toArray, "UTF-8").trim mustEqual ""
          new String(result.error.toArray, "UTF-8").trim must contain("""Error: Module "console" not found""")
      }

    }
  }

}
