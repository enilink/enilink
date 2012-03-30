package net.enilink.web.rest

import net.liftweb.http.rest.RestHelper
import net.liftweb.http.Req
import org.eclipse.core.runtime.Platform
import scala.collection.JavaConversions._
import org.eclipse.core.runtime.QualifiedName
import net.enilink.komma.model.ModelCore
import org.eclipse.core.runtime.content.IContentType
import net.enilink.core.ModelSetManager
import net.enilink.komma.core.URIImpl

object VocabRest extends RestHelper {
  /**
   * If the headers and the suffix say nothing about the
   * response type, should we default to RDF/XML.  By default,
   * no, override to change the behavior.
   */
  protected def defaultGetAsRdfXml: Boolean = false

  val mimeType = "^(.+)/(.+)$".r
  lazy val mimeTypeProp = new QualifiedName(ModelCore.PLUGIN_ID, "mimeType")
  lazy val rdfContentTypes: Map[(String, String), IContentType] = Platform.getContentTypeManager.getAllContentTypes.flatMap {
    contentType =>
      contentType.getDefaultDescription.getProperty(mimeTypeProp) match {
        case mimeType(superType, subType) => List((superType -> subType) -> contentType)
        case superType: String => List((superType -> "*") -> contentType)
        case _ => Nil
      }
  }.toMap

  protected trait RdfTest {
    def acceptsRdf(r: Req) = {
      r.weightedAccept.find(ct => rdfContentTypes.keys.find(ct.matches).isDefined).isDefined
    }

    def isRdfSuffix(suffix: String) = {
      rdfContentTypes.values.find(_.getFileSpecs(IContentType.FILE_EXTENSION_SPEC).contains(suffix)).isDefined
    }

    def testResponse_?(r: Req): Boolean = {
      (!r.acceptsStarStar && acceptsRdf(r)) ||
        ((r.weightedAccept.isEmpty || r.acceptsStarStar) &&
          (isRdfSuffix(r.path.suffix) || (r.path.suffix.length == 0 && defaultGetAsRdfXml)))
    }
  }
  
  def getContentType(r: Req) = {
    r.weightedAccept.find(ct => rdfContentTypes.keys.find(ct.matches).isDefined).isDefined
  }

  protected lazy val RdfGet = new TestGet with RdfTest
  
  def serveRdf(r: Req) = {
    val model = ModelSetManager.INSTANCE.getModelSet.getModel(URIImpl.createURI(r.uri), false)
    
  }

  serve {
    case "vocab" :: _ RdfGet req => <xml:group></xml:group>
  }
}