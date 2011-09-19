package net.enilink.lift.rdfa

import org.specs._
import org.specs.matcher._
import org.specs.specification._
import org.specs.runner.ConsoleRunner
import org.specs.runner.JUnit4

class RDFaMiscTestSpecsAsTest extends JUnit4(RDFaMiscTestSpecs)
object RDFaMiscTestSpecsRunner extends ConsoleRunner(RDFaMiscTestSpecs)

object RDFaMiscTestSpecs extends Specification {
  "XML variable scope" should {
    val s = new Scope(Nil)
    "should make distinct fresh vars" in {
      (s.fresh("x").qual == s.fresh("x").qual) must be (false)
    }

    "should find vars by name" in {
      (s.byName("x") == s.byName("x")) must be (true)
    }
  }

  "RDFa walker" should {
    "should stop chaining on bogus rel values (Test #105) " in {
      val e1 = <div xmlns:dc="http://purl.org/dc/elements/1.1/" about="" rel="dc:creator">
                 <a rel="myfoobarrel" href="ben.html">Ben</a>
                 created this page.
               </div>

      var addr = "data:"
      val undef = RDFaParser.undef
      var arcs = RDFaParser.walk(e1, addr, Name(addr), undef, Nil, Nil, null)
      (arcs.force.head match {
        case (Name(_), Name(_), XMLVar(_, _)) => true
        case _ => false
      }) must be(true)
    }
  }
}
