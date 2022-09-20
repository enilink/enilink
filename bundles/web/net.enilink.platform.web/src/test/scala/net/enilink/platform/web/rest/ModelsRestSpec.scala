package net.enilink.platform.web.rest

import com.google.inject.Guice
import net.enilink.komma.core.KommaModule
import net.enilink.komma.model._
import net.enilink.platform.lift.util.Globals
import net.liftweb.common.{Box, Full}
import net.liftweb.http.{LiftResponse, Req}
import net.liftweb.mocks.MockHttpServletRequest
import net.liftweb.mockweb.WebSpec
import org.eclipse.core.runtime.QualifiedName
import org.eclipse.core.runtime.content.{IContentDescription, IContentType}
import org.mockito
import org.specs2.mock.Mockito

object MockModelsRest extends ModelsRest with Mockito {
  // this class is required to mock the content types which are not working without OSGi

  class MockContentDescription(mimeType: (String, String), contentType: IContentType) extends IContentDescription {
    override def isRequested(qualifiedName: QualifiedName): Boolean = true

    override def getCharset: String = "UTF-8"

    override def getContentType: IContentType = contentType

    override def getProperty(qualifiedName: QualifiedName): AnyRef = {
      if (qualifiedName.getLocalName == "mimeType") {
        mimeType._1 + "/" + mimeType._2
      } else {
        null
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

  "ModelsRest" should {
    val testOptionsUrl = "http://foo.com/models"
    val testPostUrl = "http://foo.com/models/test"
    val testGetUrl = "http://foo.com/models"

    val testOptionsReq = new MockHttpServletRequest(testOptionsUrl) {
      method = "OPTIONS"
    }

    val modelsRest = MockModelsRest

    "support options request" withReqFor testOptionsReq in { req =>
      modelsRest(req)().map(_.toResponse) must beLike {
        case Full(r) if r.code == 200 => ok
      }
    }

    val testGetReq = new MockHttpServletRequest(testGetUrl) {
      method = "GET"
    }

    val testPostReq = new MockHttpServletRequest(testPostUrl) {
      method = "POST"
      body_=("<t:s> <t:p> <t:o> .", "text/turtle")
    }

    "support post request" withReqFor testPostReq in { req =>
      modelsRest(req)().map(_.toResponse) must beLike {
        case Full(r) if r.code == 200 => ok
      }
    }
  }
}