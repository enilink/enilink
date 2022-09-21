package net.enilink.platform.web.rest

import com.google.inject.Guice
import net.enilink.komma.core.KommaModule
import net.enilink.komma.model._
import net.enilink.platform.lift.util.Globals
import net.liftweb.common.{Box, Full}
import net.liftweb.http.{LiftResponse, Req}
import net.liftweb.mockweb.WebSpec
import org.eclipse.core.runtime.QualifiedName
import org.eclipse.core.runtime.content.{IContentDescription, IContentType}
import org.mockito
import org.specs2.mock.Mockito

import java.io.{ByteArrayInputStream, InputStream}
import javax.servlet.ServletInputStream

class ModelsRestSpec extends WebSpec(() => {
  // configure Lift
  // LiftRules.dispatch.append(MockModelsRest)
  // create configuration and a model set factory// create configuration and a model set factory
  val module: KommaModule = ModelPlugin.createModelSetModule(classOf[ModelPlugin].getClassLoader)
  val factory: IModelSetFactory = Guice.createInjector(new ModelSetModule(module)).getInstance(classOf[IModelSetFactory])

  // create a model set with an in-memory repository
  val modelSet: IModelSet = factory.createModelSet(MODELS.NAMESPACE_URI.appendFragment("MemoryModelSet"))
  Globals.contextModelSet.default.set(Full(modelSet))
}) with Mockito {
  sequential // This is important for using SessionVars, etc.

  val modelsRest = MockModelsRest
  val baseUrl = "http://foo.com/models"
  val testOptionsReq = new MockHttpServletRequest(baseUrl) {
    method = "OPTIONS"
  }
  val testPostReq = new MockHttpServletRequest(baseUrl + "/test-model") {
    method = "POST"
    body_=("<t:s> <t:p> <t:o> .", "text/turtle")
  }
  val testPostReqToBase = new MockHttpServletRequest(baseUrl) {
    method = "POST"
    body_=("<t:s> <t:p> <t:o> .", "text/turtle")
  }
  val testGetReq = new MockHttpServletRequest(baseUrl + "/test-model") {
    method = "GET"
    headers = (("Accept", "text/turtle" :: Nil) :: Nil).toMap
  }
  val testInvalidPostReq = new MockHttpServletRequest(baseUrl + "/test-model-invalid-data") {
    method = "POST"
    body_=("<t:s> _invalid_ <t:p> <t:o> .", "text/turtle")
  }

  "ModelsRest" should {
    "support options request" withReqFor testOptionsReq in { req =>
      modelsRest(req)().map(_.toResponse) must beLike {
        case Full(r) if r.code == 200 => ok
      }
    }

    "reject invalid post request to /models" withReqFor testPostReqToBase in { req =>
      modelsRest(req)().map(_.toResponse) must beLike {
        case Full(r) if r.code == 400 => ok
      }
    }

    "support post request" withReqFor testPostReq in { req =>
      modelsRest(req)().map(_.toResponse) must beLike {
        case Full(r) if r.code == 200 => ok
      }
    }

    "support get request" withReqFor testGetReq in { req =>
      modelsRest(req)().map(_.toResponse) must beLike {
        case Full(r) if r.code == 200 => ok
      }
    }

    "reject invalid post request" withReqFor testInvalidPostReq in { req =>
      modelsRest(req)().map(_.toResponse) must beLike {
        case Full(r) if r.code == 400 => ok
      }
    }
  }
}

object MockModelsRest extends ModelsRest with Mockito {
  // this class is required to mock the content types which are not working without OSGi

  class MockContentDescription(mimeType: (String, String), contentType: IContentType) extends IContentDescription {
    override def isRequested(qualifiedName: QualifiedName): Boolean = true

    override def getCharset: String = "UTF-8"

    override def getContentType: IContentType = contentType

    override def getProperty(qualifiedName: QualifiedName): Object = {
      qualifiedName.getLocalName match {
        case "mimeType" => mimeType._1 + "/" + mimeType._2
        case "hasWriter" => true: java.lang.Boolean
        case _ => null
      }
    }

    override def setProperty(qualifiedName: QualifiedName, o: Any): Unit = {}
  }

  override lazy val rdfContentTypes: Map[(String, String), IContentType] = List(
    createContentType("text", "turtle")
  ).toMap

  override def apply(in: Req): () => Box[LiftResponse] = {
    try {
      Globals.contextModelSet.vend.map(_.getUnitOfWork.begin)
      super.apply(in)
    } finally {
      Globals.contextModelSet.vend.map(_.getUnitOfWork.end)
    }
  }

  def createContentType(mimeType: (String, String)): ((String, String), IContentType) = {
    val contentType = mock[IContentType]
    mockito.Mockito.when(contentType.getDefaultDescription()).thenReturn(new MockContentDescription(mimeType, contentType))
    (mimeType, contentType)
  }
}

class MockHttpServletRequest(url: String) extends net.liftweb.mocks.MockHttpServletRequest(url) {
  class MockServletInputStream(is: InputStream) extends ServletInputStream {
    def read(): Int = is.read()

    override def available(): Int = is.available()

    def isFinished(): Boolean = is.available() == 0

    def isReady(): Boolean = !isFinished()

    def setReadListener(l: javax.servlet.ReadListener): Unit = ()
  }

  override def getInputStream(): ServletInputStream = {
    new MockServletInputStream(new ByteArrayInputStream(body))
  }
}