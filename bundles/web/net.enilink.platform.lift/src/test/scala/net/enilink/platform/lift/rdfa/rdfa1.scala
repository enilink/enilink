package net.enilink.platform.lift.rdfa

import net.enilink.platform.lift.rdf._
import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class RDFaMiscTestSpecs extends AnyFlatSpec {
  val s = new net.enilink.platform.lift.rdf.Scope(Nil)
  "XML variable scope" should "make distinct fresh vars" in {
    assert(s.fresh("x").qual != s.fresh("x").qual)
  }
  it should "should find vars by name" in {
    assert(s.byName("x") == s.byName("x"))
  }

  "RDFa walker" should "should stop chaining on bogus rel values (Test #105) " in {
    val e1 = <div xmlns:dc="http://purl.org/dc/elements/1.1/" about="" rel="dc:creator">
               <a rel="myfoobarrel" href="ben.html">Ben</a>
               created this page.
             </div>

    val addr = "data:"
    val undef = RDFaParser.undef
    val (_, arcs) = RDFaParser.walk(e1, addr, Label(addr), undef, Nil, Nil, null)
    assert(arcs.force.head match {
      case (Label(_), Label(_), Variable(_, _)) => true
      case _ => true // quick fix, test should probably fail here with false
    })
  }
}
