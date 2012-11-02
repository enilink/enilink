package net.enilink.web.rest

import java.io.ByteArrayOutputStream
import scala.Array.canBuildFrom
import scala.collection.JavaConversions.mapAsJavaMap
import org.eclipse.core.runtime.Platform
import org.eclipse.core.runtime.QualifiedName
import org.eclipse.core.runtime.content.IContentDescription
import org.eclipse.core.runtime.content.IContentType
import net.enilink.komma.model.ModelCore
import net.enilink.komma.core.URI
import net.enilink.komma.core.URIImpl
import net.enilink.core.ModelSetManager
import net.enilink.lift.util.Globals
import net.liftweb.common.Box
import net.liftweb.common.Box.option2Box
import net.liftweb.common.Full
import net.liftweb.http.ContentType
import net.liftweb.http.InMemoryResponse
import net.liftweb.http.LiftResponse
import net.liftweb.http.LiftRules
import net.liftweb.http.LiftRulesMocker.toLiftRules
import net.liftweb.http.NotFoundResponse
import net.liftweb.http.Req
import net.liftweb.http.S
import net.liftweb.http.rest.RestHelper
import net.liftweb.common.Empty
import net.liftweb.http.ForbiddenResponse
import net.enilink.lift.util.NotAllowedModel

object ModelsRest extends RestHelper {
  /**
   * Simple in-memory response for RDF data.
   */
  case class RdfResponse(data: Array[Byte], contentDescription: IContentDescription, headers: List[(String, String)], code: Int) extends LiftResponse {
    def toResponse = {
      val typeName = contentDescription.getProperty(mimeTypeProp)
      InMemoryResponse(data, ("Content-Length", data.length.toString) :: ("Content-Type", typeName + "; charset=utf-8") :: headers, Nil, code)
    }
  }

  /**
   * If the headers and the suffix say nothing about the
   * response type, should we default to RDF/XML.  By default,
   * no, override to change the behavior.
   */
  protected def defaultGetAsRdfXml: Boolean = false

  /**
   * Retrieve all registered RDF content types (those with a special mimeType property) and store them in a map.
   */
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

  /**
   * Tests requests for wanting RDF data.
   */
  protected trait RdfTest {
    def testResponse_?(r: Req): Boolean = getResponseContentType(r) isDefined
  }

  /**
   * Find best matching content type for the given requested types.
   */
  def matchType(requestedTypes: List[ContentType]) = {
    object FindContentType {
      // extractor for partial function below
      def unapply(ct: ContentType) = rdfContentTypes.find(e => ct.matches(e._1))
    }
    requestedTypes.collectFirst { case FindContentType(key, value) => (key, value) }
  }

  /**
   * Find best matching content type for the suffix of the request URI.
   */
  def matchTypeByExtension(extension: String) = {
    rdfContentTypes.find(_._2.getFileSpecs(IContentType.FILE_EXTENSION_SPEC).contains(extension))
  }

  def getResponseContentType(r: Req): Option[IContentType] = {
    // use list given by "type" parameter
    S.param("type").map(ContentType.parse(_)).flatMap(matchType(_).map(_._2)) or {
      // use file extension or accept-header content negotiation
      val uri = getUri(r)
      if ((r.weightedAccept.isEmpty || r.acceptsStarStar) && uri.fileExtension == null && defaultGetAsRdfXml) {
        Some(Platform.getContentTypeManager.getContentType("net.enilink.komma.contenttype.rdfxml"))
      } else {
        (if (uri.fileExtension != null) matchTypeByExtension(uri.fileExtension) else None) match {
          case Some((mimeType, cType)) if r.acceptsStarStar || r.weightedAccept.find(_.matches(mimeType)).isDefined => Some(cType)
          case _ => matchType(r.weightedAccept).map(_._2)
        }
      }
    }
  }

  def acceptsHtml(r: Req) = r.weightedAccept.find(_.matches("text" -> "html")).isDefined

  def getUri(r: Req) = Globals.contextModel.vend.dmap(URIImpl.createURI((r.hostName match {
    // support replacement for local installations (development mode)
    case "localhost" | "127.0.0.1" => "http://enilink.net"
    case _ => r.hostAndPath
  }) + r.uri): URI)(_.getURI)

  def getModel(modelUri: URI) = {
    val modelSet = ModelSetManager.INSTANCE.getModelSet
    Box.legacyNullTest(modelSet.getModel(modelUri, false)) or {
      if (modelUri.fileExtension != null) Box.legacyNullTest(modelSet.getModel(modelUri.trimFileExtension, false)) else Empty
    }
  }

  protected lazy val RdfGet = new TestGet with RdfTest

  /**
   * Serialize and return RDF data according to the requested content type.
   */
  def serveRdf(r: Req, modelUri: URI) = {
    getModel(modelUri).dmap(Full(new NotFoundResponse("Model " + modelUri + " not found.")): Box[LiftResponse])(model =>
      model match {
        case NotAllowedModel(_) => Full(ForbiddenResponse("You don't have permissions to access " + model.getURI + "."))
        case _ => getResponseContentType(r) map (_.getDefaultDescription) match {
          case Some(cd) =>
            val baos = new ByteArrayOutputStream
            model.save(baos, Map(classOf[IContentDescription] -> cd))
            Full(new RdfResponse(baos.toByteArray, cd, Nil, 200))
          case _ => None
        }
      })
  }

  serve {
    case "vocab" :: modelName RdfGet req if !modelName.isEmpty && modelName != List("index") || Globals.contextModel.vend.isDefined => {
      val modelUri = getUri(req)
      if (S.param("type").isEmpty && acceptsHtml(req)) Globals.contextModel.doWith(getModel(modelUri)) {
        Globals.contextResource.doWith(Globals.contextModel.vend.map(_.resolve(modelUri))) {
          LiftRules.convertResponse(S.runTemplate(List("static", "ontology")), Nil, S.responseCookies, req)
        }
      }
      else serveRdf(req, modelUri)
    }
  }
}