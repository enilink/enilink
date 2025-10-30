package net.enilink.platform.web.rest

import com.google.inject.Guice
import net.enilink.komma.core.{KommaModule, Statement, URI, URIs}
import net.enilink.komma.model._
import net.enilink.platform.lift.util.Globals
import net.liftweb.common.{Box, Full}
import net.liftweb.http.provider.servlet.HTTPRequestServlet
import net.liftweb.http.{BasicResponse, InMemoryResponse, LiftResponse, OutputStreamResponse, Req}
import org.junit.jupiter.api.{AfterAll, BeforeAll, Test}
import org.junit.jupiter.api.Assertions._

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import javax.servlet.http.HttpServletRequest

/**
 * Companion object of unit tests for the /sparql endpoint
 */
object SparqlRestTest {
  var modelSet: IModelSet = _
  val testModel: URI = MODELS.NAMESPACE_URI.appendFragment("test-model")

  @BeforeAll
  def setup() : Unit = {
    val module: KommaModule = ModelPlugin.createModelSetModule(classOf[ModelPlugin].getClassLoader)
    val factory: IModelSetFactory = Guice.createInjector(new ModelSetModule(module)).getInstance(classOf[IModelSetFactory])
    modelSet = factory.createModelSet(MODELS.NAMESPACE_URI.appendFragment("MemoryModelSet"))
    Globals.contextModelSet.default.set(Full(modelSet))
    // create a test model and add some data
    val model = modelSet.createModel(testModel)
    val em = model.getManager
    em.add(new Statement(URIs.createURI("t:s"), URIs.createURI("t:p"), URIs.createURI("t:o")))
  }

  @AfterAll
  def tearDown() : Unit = {
    modelSet.dispose()
    modelSet = null
  }
}

/**
 * Unit tests for the /sparql endpoint
 */
class SparqlRestTest {
  def sparqlRest(req : Req): () => Box[LiftResponse] = {
    MockSparqlRest(req)
  }
  val baseUrl = "http://foo.com/sparql"

  def toReq(httpRequest: HttpServletRequest): Req = {
    Req(new HTTPRequestServlet(httpRequest, null), Nil, System.nanoTime)
  }

  @Test
  def selectQuery(): Unit = {
    val req = new MockHttpServletRequest(baseUrl) {
      method = "GET"
      parameters = List("query" -> "SELECT ?s ?p ?o WHERE { ?s ?p ?o }", "model" -> SparqlRestTest.testModel.toString)
      headers = Map("Accept" -> List("application/sparql-results+json"))
    }
    val response = sparqlRest(toReq(req))().map(_.toResponse)
    assertEquals(200, response.map(_.code).getOrElse(-1))
    val body = response.map(responseToString).getOrElse("")
    assertTrue(body.contains("t:s"))
    assertTrue(body.contains("t:p"))
    assertTrue(body.contains("t:o"))
  }

  @Test
  def constructQuery(): Unit = {
    val req = new MockHttpServletRequest(baseUrl) {
      method = "GET"
      parameters = List("query" -> "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }", "model" -> SparqlRestTest.testModel.toString)
      headers = Map("Accept" -> List("text/turtle"))
    }
    val response = sparqlRest(toReq(req))().map(_.toResponse)
    assertEquals(200, response.map(_.code).getOrElse(-1))
    val body = response.map(responseToString).getOrElse("")
    assertTrue(body.contains("t:s"))
    assertTrue(body.contains("t:p"))
    assertTrue(body.contains("t:o"))
  }

  @Test
  def askQuery(): Unit = {
    val req = new MockHttpServletRequest(baseUrl) {
      method = "GET"
      parameters = List("query" -> "ASK WHERE { ?s ?p ?o }", "model" -> SparqlRestTest.testModel.toString)
      headers = Map("Accept" -> List("application/sparql-results+xml"))
    }
    val response = sparqlRest(toReq(req))().map(_.toResponse)
    assertEquals(200, response.map(_.code).getOrElse(-1))
    val body = response.map(responseToString).getOrElse("")
    assertTrue(body.contains("true"))
  }

  @Test
  def updateQuery(): Unit = {
    val req = new MockHttpServletRequest(baseUrl) {
      method = "POST"
      parameters = List("update" -> "INSERT DATA { <t:s2> <t:p2> <t:o2> }", "model" -> SparqlRestTest.testModel.toString)
      contentType = "application/x-www-form-urlencoded"
    }
    assertEquals(200, sparqlRest(toReq(req))().map(_.toResponse.code).getOrElse(-1))
    // verify data was inserted
    val selectReq = new MockHttpServletRequest(baseUrl) {
      method = "GET"
      parameters = List("query" -> "SELECT ?s ?p ?o WHERE { ?s ?p ?o }", "model" -> SparqlRestTest.testModel.toString)
      headers = Map("Accept" -> List("application/sparql-results+json"))
    }
    val body = sparqlRest(toReq(selectReq))().map(_.toResponse).map(responseToString).getOrElse("")
    assertTrue(body.contains("t:s2"))
    assertTrue(body.contains("t:p2"))
    assertTrue(body.contains("t:o2"))
  }

  def responseToString(resp: BasicResponse): String = {
    resp match {
      case InMemoryResponse(data, _, _, _) =>
        new String(data, StandardCharsets.UTF_8)
      case r: OutputStreamResponse =>
        val rStream = new ByteArrayOutputStream()
        r.out(rStream)
        rStream.toString()
      case _ => fail("Unexpected response type"); ""
    }
  }
}
