package net.enilink.platform.web.rest

import net.enilink.komma.core.URIs
import net.enilink.platform.lift.util.Globals
import net.liftweb.common.{Box, Empty, Full}
import net.liftweb.http.rest.RestHelper
import net.liftweb.http.{CurrentReq, LiftResponse, Req, S}
import org.eclipse.core.runtime.{QualifiedName, RegistryFactory}
import org.eclipse.core.runtime.content.{IContentDescription, IContentType}
import org.mockito.Mockito

import java.io.{ByteArrayInputStream, InputStream}
import javax.servlet.ServletInputStream

trait MockModelsInApply extends RestHelper {
  override def apply(in: Req): () => Box[LiftResponse] = {
    try {
      Globals.contextModelSet.vend.map(_.getUnitOfWork.begin)
      CurrentReq.doWith(in) {
        val m = S.param("model").flatMap(name => Full(URIs.createURI(name)).filterNot(_.isRelative))
        Globals.contextModel.doWith(() => m.flatMap(uri => {
          Globals.contextModelSet.vend.map(_.getModel(uri, false)).filter(_ != null)
        })) {
          super.apply(in)
        }
      }
    } finally {
      Globals.contextModelSet.vend.map(_.getUnitOfWork.end)
    }
  }
}

object MockModelsRest extends ModelsRest with MockModelsInApply{
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

  // directl load content type extensions as content type manager is not available without OSGi
  override lazy val rdfContentTypes: Map[(String, String), IContentType] = RegistryFactory.getRegistry
    .getConfigurationElementsFor("org.eclipse.core.contenttype", "contentTypes")
    .filter(_.getName == "content-type")
    .filter(_.getAttribute("base-type") == "net.enilink.komma.contenttype.rdf")
    .flatMap(_.getChildren("property")
      .filter(_.getAttribute("name") == "mimeType")
      .map(_.getAttribute("default")))
    .filter(_ != null)
    .map(_.split("/")).map(elements => createContentType(elements(0), elements(1)))
    .toMap

  def createContentType(mimeType: (String, String)): ((String, String), IContentType) = {
    val contentType = Mockito.mock(classOf[IContentType])
    Mockito.when(contentType.getDefaultDescription()).thenReturn(new MockContentDescription(mimeType, contentType))
    (mimeType, contentType)
  }
}

object MockSparqlRest extends SparqlRest with MockModelsInApply {
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