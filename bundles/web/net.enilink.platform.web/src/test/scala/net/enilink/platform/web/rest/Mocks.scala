package net.enilink.platform.web.rest

import net.enilink.platform.lift.util.Globals
import net.liftweb.common.Box
import net.liftweb.http.{LiftResponse, Req}
import org.eclipse.core.runtime.QualifiedName
import org.eclipse.core.runtime.content.{IContentDescription, IContentType}
import org.mockito.Mockito

import java.io.{ByteArrayInputStream, InputStream}
import javax.servlet.ServletInputStream

object MockModelsRest extends ModelsRest {
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
    val contentType = Mockito.mock(classOf[IContentType])
    Mockito.when(contentType.getDefaultDescription()).thenReturn(new MockContentDescription(mimeType, contentType))
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