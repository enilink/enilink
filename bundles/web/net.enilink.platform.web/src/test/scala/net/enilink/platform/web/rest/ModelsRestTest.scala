package net.enilink.platform.web.rest

import com.google.inject.Guice
import net.enilink.komma.core.KommaModule
import net.enilink.komma.model.{IModelSet, IModelSetFactory, MODELS, ModelPlugin, ModelSetModule}
import net.enilink.platform.lift.util.Globals
import net.liftweb.common.{Box, Full}
import net.liftweb.http.{LiftResponse, Req}
import net.liftweb.http.provider.servlet.HTTPRequestServlet
import org.junit.Assert._
import org.junit.{AfterClass, BeforeClass, Test}

import javax.servlet.http.HttpServletRequest

/**
 * Companion object of unit tests for the /models endpoint
 */
object ModelsRestTest {
  var modelSet : IModelSet = null

  @BeforeClass
  def setup() : Unit = {
    // create configuration and a model set factory
    val module: KommaModule = ModelPlugin.createModelSetModule(classOf[ModelPlugin].getClassLoader)
    val factory: IModelSetFactory = Guice.createInjector(new ModelSetModule(module)).getInstance(classOf[IModelSetFactory])

    // create a model set with an in-memory repository
    modelSet = factory.createModelSet(MODELS.NAMESPACE_URI.appendFragment("MemoryModelSet"))
    Globals.contextModelSet.default.set(Full(modelSet))
  }

  @AfterClass
  def tearDown() : Unit = {
    modelSet.dispose()
    modelSet = null
  }
}
/**
 * Unit tests for the /models endpoint
 */
class ModelsRestTest {
  def modelsRest(req : Req): () => Box[LiftResponse] = {
    MockModelsRest(req)
  }
  val baseUrl = "http://foo.com/models"

  def toReq(httpRequest: HttpServletRequest): Req = {
    Req(new HTTPRequestServlet(httpRequest, null), Nil, System.nanoTime)
  }

  @Test
  def optionsRequest(): Unit = {
    // support options request
    val req = new MockHttpServletRequest(baseUrl) {
      method = "OPTIONS"
    }
    assertEquals(Full(200), modelsRest(toReq(req))().map(_.toResponse.code))
  }

  @Test
  def postAndGetRequest(): Unit = {
    // support post request
    val postReq = new MockHttpServletRequest(baseUrl + "/test-model") {
      method = "POST"
      body_=("<t:s> <t:p> <t:o> .", "text/turtle")
    }
    assertEquals(Full(200), modelsRest(toReq(postReq))().map(_.toResponse.code))

    // support get request
    val getReq = new MockHttpServletRequest(baseUrl + "/test-model") {
      method = "GET"
      headers = (("Accept", "text/turtle" :: Nil) :: Nil).toMap
    }
    assertEquals(Full(200), modelsRest(toReq(getReq))().map(_.toResponse.code))
  }

  @Test
  def postAndGetJsonLD(): Unit = {
    val postReq = new MockHttpServletRequest(baseUrl + "/test-model") {
      method = "POST"
      body_=("""{"@id": "t:s",  "t:p": {"@id": "t:o"}}""", "application/ld+json")
    }
    assertEquals(Full(200), modelsRest(toReq(postReq))().map(_.toResponse.code))

    // support get request
    val getReq = new MockHttpServletRequest(baseUrl + "/test-model") {
      method = "GET"
      headers = (("Accept", "application/ld+json" :: Nil) :: Nil).toMap
    }
    assertEquals(Full(200), modelsRest(toReq(getReq))().map(_.toResponse.code))
  }

  @Test
  def invalidPostRequestToBase(): Unit = {
    // reject invalid post request to /models
    val postReqToBase = new MockHttpServletRequest(baseUrl) {
      method = "POST"
      body_=("<t:s> <t:p> <t:o> .", "text/turtle")
    }
    assertEquals(Full(400), modelsRest(toReq(postReqToBase))().map(_.toResponse.code))
  }

  @Test
  def postRequestInvalidData(): Unit = {
    // reject post request with invalid data
    val invalidPostReq = new MockHttpServletRequest(baseUrl + "/test-model-invalid-data") {
      method = "POST"
      body_=("<t:s> _invalid_ <t:p> <t:o> .", "text/turtle")
    }
    assertEquals(Full(400), modelsRest(toReq(invalidPostReq))().map(_.toResponse.code))
  }
}