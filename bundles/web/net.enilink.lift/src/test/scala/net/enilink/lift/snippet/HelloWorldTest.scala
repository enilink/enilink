package net.enilink.lift
package snippet

import org.specs.runner.ConsoleRunner
import org.specs.runner.JUnit4
import org.specs.specification.Examples
import org.specs.Specification

import lib.Globals
import net.liftweb.common.Empty
import net.liftweb.http.LiftSession
import net.liftweb.http.S
import net.liftweb.util.Helpers.now
import net.liftweb.util.Helpers.randomString

class HelloWorldTestSpecsAsTest extends JUnit4(HelloWorldTestSpecs)
object HelloWorldTestSpecsRunner extends ConsoleRunner(HelloWorldTestSpecs)

object HelloWorldTestSpecs extends Specification {
  val session = new LiftSession("", randomString(20), Empty)
  val stableTime = now

  override def executeExpectations(ex: Examples, t: => Any): Any = {
    S.initIfUninitted(session) {
      Globals.time.doWith(stableTime) {
        super.executeExpectations(ex, t)
      }
    }
  }

  "HelloWorld Snippet" should {
    "Put the time in the node" in {
      val hello = new HelloWorld
      Thread.sleep(1000) // make sure the time changes

      val str = hello.howdy(<span>Welcome to your Lift app at <span id="time">Time goes here</span></span>).toString

      str.indexOf(stableTime.toString) must be >= 0
      str.indexOf("Hello at") must be >= 0
    }
  }
}
