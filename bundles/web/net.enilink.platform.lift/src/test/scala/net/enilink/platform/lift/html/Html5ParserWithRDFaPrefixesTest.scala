package net.enilink.platform.lift.html

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Html5ParserWithRDFaPrefixesTest {
  private object Parser extends Html5ParserWithRDFaPrefixes

  @Test
  def parseSimpleDocument(): Unit = {
    val result = Parser.parse(
      """<!DOCTYPE html>
        |<html prefix="ex: http://example.com/">
        |  <head>
        |   <title>Test</title>
        |  </head>
        |  <body>
        |    <span property="ex:property">Value</span>
        |  </body>
        |</html>""".stripMargin
    )
    assertTrue(result.isDefined)
    result.foreach { html =>
      assertEquals("html", html.label)
      assertEquals("Test", (html \ "head" \ "title").text)
      assertEquals("http://example.com/", (html \\ "span")
        .find(node => (node \@ "property") == "ex:property")
        .map(_.scope.getURI("ex")).getOrElse(fail("No span with property 'ex:property' found")))
    }
    //assertEquals("http://example.com/", html.scope.getURI("ex"))
  }
}
