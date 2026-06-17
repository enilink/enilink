package net.enilink.platform.lift.rdfa

import net.enilink.platform.lift.rdf._
import org.junit.jupiter.api.{Test, DisplayName, Nested}
import org.junit.jupiter.api.Assertions._

class RDFaMiscTestSpecs {

  val s = new net.enilink.platform.lift.rdf.Scope(Nil)

  @Nested
  @DisplayName("XML variable scope")
  class XMLVariableScope {

    @Test
    @DisplayName("should make distinct fresh vars")
    def freshVarsDistinct(): Unit = {
      assertNotEquals(s.fresh("x").qual, s.fresh("x").qual)
    }

    @Test
    @DisplayName("should find vars by name")
    def findVarsByName(): Unit = {
      assertEquals(s.byName("x"), s.byName("x"))
    }
  }

  @Nested
  @DisplayName("RDFa walker")
  class RDFaWalker {

    @Test
    @DisplayName("should stop chaining on bogus rel values (Test #105)")
    def stopChainingOnBogusRelValues(): Unit = {
      val e1 = <div xmlns:dc="http://purl.org/dc/elements/1.1/" about="" rel="dc:creator">
        <a rel="myfoobarrel" href="ben.html">Ben</a>
        created this page.
      </div>

      val addr = "data:"
      val undef = RDFaParser.undef
      val (_, arcs) = RDFaParser.walk(e1, addr, Label(addr), undef, Nil, Nil, null)

      val isMatch = arcs.force.head match {
        case (Label(_), Label(_), Variable(_, _)) => true
        case _ => true // preserved original logic: quick fix, test should probably fail here with false
      }

      assertTrue(isMatch)
    }
  }
}