package net.enilink.platform.web.rest

import com.google.inject.Guice
import net.enilink.komma.core.{KommaModule, Statement, URI, URIs}
import net.enilink.komma.model._
import net.enilink.platform.core.security.ISecureModelSet
import net.enilink.platform.lift.util.Globals
import net.liftweb.common.{Box, Full}
import net.liftweb.http.provider.servlet.HTTPRequestServlet
import net.liftweb.http.{BasicResponse, InMemoryResponse, LiftResponse, OutputStreamResponse, Req}
import org.junit.jupiter.api.{AfterAll, BeforeAll, Test}
import org.junit.jupiter.api.Assertions._
import org.eclipse.rdf4j.query.{MalformedQueryException, QueryEvaluationException, QueryInterruptedException}
import org.mockito.ArgumentMatchers.{any => mAny, eq => mEq}
import org.mockito.Mockito

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
  def queryWithoutModel(): Unit = {
    val req = new MockHttpServletRequest(baseUrl) {
      method = "GET"
      parameters = List("query" -> "SELECT ?s ?p ?o WHERE { ?s ?p ?o }")
      headers = Map("Accept" -> List("application/sparql-results+json"))
    }
    val response = sparqlRest(toReq(req))().map(_.toResponse)
    assertPlainTextError(response, 400, "MISSING_MODEL: The required parameter 'model' is missing.")
  }

  @Test
  def postQueryWithoutModel(): Unit = {
    val req = new MockHttpServletRequest(baseUrl) {
      method = "POST"
      parameters = List("query" -> "SELECT ?s ?p ?o WHERE { ?s ?p ?o }")
      contentType = "application/x-www-form-urlencoded"
      headers = Map("Accept" -> List("application/sparql-results+json"))
    }
    val response = sparqlRest(toReq(req))().map(_.toResponse)
    assertPlainTextError(response, 400, "MISSING_MODEL: The required parameter 'model' is missing.")
  }

  @Test
  def queryWithoutQueryParameter(): Unit = {
    val req = new MockHttpServletRequest(baseUrl) {
      method = "GET"
      parameters = List("model" -> SparqlRestTest.testModel.toString)
      headers = Map("Accept" -> List("application/sparql-results+json"))
    }
    val response = sparqlRest(toReq(req))().map(_.toResponse)
    assertPlainTextError(response, 400, "MISSING_QUERY: The required parameter 'query' is missing.")
  }

  @Test
  def malformedQueryUsesPlainTextEnvelope(): Unit = {
    val req = new MockHttpServletRequest(baseUrl) {
      method = "GET"
      parameters = List(
        "query" -> "SELECT WHERE { ?s ?p ?o }",
        "model" -> SparqlRestTest.testModel.toString
      )
      headers = Map("Accept" -> List("application/sparql-results+json"))
    }
    val response = sparqlRest(toReq(req))().map(_.toResponse)
    assertPlainTextError(response, 400, "MALFORMED_QUERY:")
  }

  @Test
  def postSparqlQueryWithEmptyBodyIsRejected(): Unit = {
    val req = new MockHttpServletRequest(baseUrl) {
      method = "POST"
      parameters = List("model" -> SparqlRestTest.testModel.toString)
      contentType = "application/sparql-query"
      body = Array.emptyByteArray
      headers = Map("Accept" -> List("application/sparql-results+json"))
    }
    val response = sparqlRest(toReq(req))().map(_.toResponse)
    assertPlainTextError(response, 400, "INVALID_QUERY_REQUEST: The query request structure is invalid.")
  }

  @Test
  def postSparqlUpdateWithEmptyBodyIsRejected(): Unit = {
    val req = new MockHttpServletRequest(baseUrl) {
      method = "POST"
      parameters = List("model" -> SparqlRestTest.testModel.toString)
      contentType = "application/sparql-update"
      body = Array.emptyByteArray
      headers = Map("Accept" -> List("application/sparql-results+json"))
    }
    val response = sparqlRest(toReq(req))().map(_.toResponse)
    assertPlainTextError(response, 400, "INVALID_QUERY_REQUEST: The query request structure is invalid.")
  }

  @Test
  def postWithoutQueryOrUpdateIsRejected(): Unit = {
    val req = new MockHttpServletRequest(baseUrl) {
      method = "POST"
      parameters = List("model" -> SparqlRestTest.testModel.toString)
      contentType = "application/x-www-form-urlencoded"
      headers = Map("Accept" -> List("application/sparql-results+json"))
    }
    val response = sparqlRest(toReq(req))().map(_.toResponse)
    assertPlainTextError(response, 400, "INVALID_QUERY_REQUEST: The query request structure is invalid.")
  }

  @Test
  def unsupportedPostContentTypeIsRejected(): Unit = {
    val req = new MockHttpServletRequest(baseUrl) {
      method = "POST"
      parameters = List("model" -> SparqlRestTest.testModel.toString)
      contentType = "text/plain"
      body = "SELECT ?s WHERE { ?s ?p ?o }".getBytes(StandardCharsets.UTF_8)
      headers = Map("Accept" -> List("application/sparql-results+json"))
    }
    val response = sparqlRest(toReq(req))().map(_.toResponse)
    assertPlainTextError(response, 400, "INVALID_QUERY_REQUEST: The query request structure is invalid.")
  }

  @Test
  def conflictingQueryAndUpdateShapeIsRejected(): Unit = {
    val req = new MockHttpServletRequest(baseUrl) {
      method = "POST"
      parameters = List(
        "query" -> "SELECT ?s WHERE { ?s ?p ?o }",
        "update" -> "INSERT DATA { <t:s3> <t:p3> <t:o3> }",
        "model" -> SparqlRestTest.testModel.toString
      )
      contentType = "application/x-www-form-urlencoded"
      headers = Map("Accept" -> List("application/sparql-results+json"))
    }
    val response = sparqlRest(toReq(req))().map(_.toResponse)
    assertPlainTextError(response, 400, "INVALID_QUERY_REQUEST: The query request structure is invalid.")
  }

  @Test
  def invalidModelUriReturnsModelNotFound(): Unit = {
    val req = new MockHttpServletRequest(baseUrl) {
      method = "GET"
      parameters = List(
        "query" -> "SELECT ?s ?p ?o WHERE { ?s ?p ?o }",
        "model" -> "not a valid uri"
      )
      headers = Map("Accept" -> List("application/sparql-results+json"))
    }
    val response = sparqlRest(toReq(req))().map(_.toResponse)
    assertPlainTextError(response, 404, "MODEL_NOT_FOUND: No model found for the given identifier.")
  }

  @Test
  def unknownModelReturnsNotFound(): Unit = {
    val req = new MockHttpServletRequest(baseUrl) {
      method = "GET"
      parameters = List(
        "query" -> "SELECT ?s ?p ?o WHERE { ?s ?p ?o }",
        "model" -> MODELS.NAMESPACE_URI.appendFragment("missing-model").toString
      )
      headers = Map("Accept" -> List("application/sparql-results+json"))
    }
    val response = sparqlRest(toReq(req))().map(_.toResponse)
    assertPlainTextError(response, 404, "MODEL_NOT_FOUND: No model found for the given identifier.")
  }

  @Test
  def unsupportedAcceptHeaderReturnsExplicitError(): Unit = {
    val req = new MockHttpServletRequest(baseUrl) {
      method = "GET"
      parameters = List(
        "query" -> "SELECT ?s ?p ?o WHERE { ?s ?p ?o }",
        "model" -> SparqlRestTest.testModel.toString
      )
      headers = Map("Accept" -> List("application/x-unsupported-result"))
    }
    val response = sparqlRest(toReq(req))().map(_.toResponse)
    assertPlainTextError(response, 406, "UNSUPPORTED_ACCEPT: The requested result format is not supported.")
  }

  @Test
  def interruptedQueryMapsToQueryTimeoutEnvelope(): Unit = {
    val response = new SparqlRest().toResponse(new Exception(new QueryInterruptedException("timed out")))
    assertPlainTextError(Some(response.toResponse), 503, "QUERY_TIMEOUT: Query execution exceeded the time limit.")
  }

  @Test
  def evaluationFailureMapsToQueryEvaluationEnvelope(): Unit = {
    val response = new SparqlRest().toResponse(new Exception(new QueryEvaluationException("evaluation failed")))
    assertPlainTextError(Some(response.toResponse), 500, "QUERY_EVALUATION_ERROR: Query evaluation failed.")
  }

  @Test
  def nonRdf4jFailureMapsToInternalErrorEnvelope(): Unit = {
    val response = new SparqlRest().toResponse(new RuntimeException("boom"))
    assertPlainTextError(Some(response.toResponse), 500, "INTERNAL_ERROR: Internal server error.")
  }

  @Test
  def malformedQueryInNestedCauseStillMapsToMalformedEnvelope(): Unit = {
    val response = new SparqlRest().toResponse(new RuntimeException("outer", new MalformedQueryException("syntax")))
    assertPlainTextError(Some(response.toResponse), 400, "MALFORMED_QUERY:")
  }

  @Test
  def forbiddenModelAccessStillReturns403(): Unit = {
    val forbiddenModelUri = MODELS.NAMESPACE_URI.appendFragment("forbidden-model")
    val contextModelSet = Mockito.mock(classOf[IModelSet])
    val secureModelSet = Mockito.mock(classOf[ISecureModelSet])
    val model = Mockito.mock(classOf[IModel])

    Mockito.when(contextModelSet.getModel(mEq(forbiddenModelUri), mEq(false))).thenReturn(model)
    Mockito.when(model.getURI).thenReturn(forbiddenModelUri)
    Mockito.when(model.getModelSet).thenReturn(secureModelSet)
    Mockito.when(secureModelSet.isReadableBy(mAny(), mAny())).thenReturn(false)

    Globals.contextModelSet.default.set(Full(contextModelSet))
    try {
      val response = new SparqlRest().queryModel("SELECT ?s WHERE { ?s ?p ?o }", forbiddenModelUri, "application/sparql-results+json")
        .map(_.toResponse)
      assertPlainTextError(response, 403, "FORBIDDEN: You don't have permissions to access")
    } finally {
      Globals.contextModelSet.default.set(Full(SparqlRestTest.modelSet))
    }
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

  def assertPlainTextError(response: Option[BasicResponse], expectedCode: Int, expectedPrefix: String): Unit = {
    assertEquals(expectedCode, response.map(_.code).getOrElse(-1))
    val contentType = response
      .flatMap(_.headers.find(_._1.equalsIgnoreCase("Content-Type")).map(_._2))
      .getOrElse("")
    assertTrue(contentType.toLowerCase.startsWith("text/plain"), s"Expected text/plain content type but got: '$contentType'")
    val body = response.map(responseToString).getOrElse("")
    assertTrue(body.startsWith(expectedPrefix), s"Expected prefix '$expectedPrefix' but got: $body")
  }
}
