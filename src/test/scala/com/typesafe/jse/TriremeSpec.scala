package com.typesafe.jse

import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import org.specs2.mutable.Specification
import com.typesafe.jse.Engine.JsExecutionResult
import java.io.File
import scala.collection.immutable
import akka.pattern.ask
import org.specs2.time.NoTimeConversions
import akka.util.Timeout
import akka.actor.{ActorRef, ActorSystem}
import scala.concurrent.Await
import java.util.concurrent.TimeUnit

@RunWith(classOf[JUnitRunner])
class TriremeSpec extends Specification with NoTimeConversions {

  def withEngine[T](block: ActorRef => T): T = {
    val system = ActorSystem()
    val engine = system.actorOf(Trireme.props())
    block(engine)
  }

  "The Trireme engine" should {
    "execute some javascript by passing in a string arg and comparing its return value" in {
      withEngine {
        engine =>
          val f = new File(classOf[TriremeSpec].getResource("test-node.js").toURI)
          implicit val timeout = Timeout(5000L, TimeUnit.MILLISECONDS)

          val futureResult = (engine ? Engine.ExecuteJs(f, immutable.Seq("999"), timeout.duration)).mapTo[JsExecutionResult]
          val result = Await.result(futureResult, timeout.duration)
          new String(result.output.toArray, "UTF-8").trim must_== "999"
          new String(result.error.toArray, "UTF-8").trim must_== ""
      }
    }

    "execute some javascript by passing in a string arg and comparing its return value expecting an error" in {
      withEngine {
        engine =>
          val f = new File(classOf[TriremeSpec].getResource("test-rhino.js").toURI)
          implicit val timeout = Timeout(5000L, TimeUnit.MILLISECONDS)

          val futureResult = (engine ? Engine.ExecuteJs(f, immutable.Seq("999"), timeout.duration)).mapTo[JsExecutionResult]
          val result = Await.result(futureResult, timeout.duration)
          new String(result.output.toArray, "UTF-8").trim must_== ""
          new String(result.error.toArray, "UTF-8").trim must startWith("""ReferenceError: "readFile" is not defined""")
      }
    }
  }

}
