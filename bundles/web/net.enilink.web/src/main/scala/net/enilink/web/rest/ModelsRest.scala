package net.enilink.web.rest

import java.io.ByteArrayOutputStream
import scala.Array.canBuildFrom
import scala.collection.JavaConversions.mapAsJavaMap
import org.eclipse.core.runtime.Platform
import org.eclipse.core.runtime.QualifiedName
import org.eclipse.core.runtime.content.IContentDescription
import org.eclipse.core.runtime.content.IContentType
import net.enilink.komma.core.URI
import net.enilink.komma.core.URIs
import net.enilink.komma.model.ModelPlugin
import net.enilink.lift.util.Globals
import net.enilink.lift.util.NotAllowedModel
import net.liftweb.common.Box
import net.liftweb.common.Box.box2Option
import net.liftweb.common.Box.option2Box
import net.liftweb.common.Empty
import net.liftweb.common.Full
import net.liftweb.http.ContentType
import net.liftweb.http.ForbiddenResponse
import net.liftweb.http.InMemoryResponse
import net.liftweb.http.LiftResponse
import net.liftweb.http.NotFoundResponse
import net.liftweb.http.Req
import net.liftweb.http.S
import net.liftweb.http.rest.RestHelper
import net.enilink.komma.model.IModel
import net.liftweb.http.JsonResponse
import net.liftweb.json._
import net.liftweb.json.JsonAST._
import net.liftweb.json.JsonDSL._
import net.liftweb.http.OnDiskFileParamHolder
import java.io.ByteArrayInputStream
import java.io.InputStream
import net.liftweb.http.BadResponse
import net.liftweb.http.OkResponse
import net.liftweb.common.Failure

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
  lazy val mimeTypeProp = new QualifiedName(ModelPlugin.PLUGIN_ID, "mimeType")
  lazy val rdfContentTypes: Map[(String, String), IContentType] = Platform.getContentTypeManager.getAllContentTypes.flatMap {
    contentType =>
      contentType.getDefaultDescription.getProperty(mimeTypeProp).asInstanceOf[String] match {
        case null => Nil
        case mimeType(superType, subType) => List((superType -> subType) -> contentType)
        case superType: String => List((superType -> "*") -> contentType)
        case _ => Nil
      }
  }.toMap

  /**
   * Tests requests for wanting RDF data.
   */
  protected trait RdfTest {
    def testResponse_?(r: Req): Boolean = getResponseContentType(r).isDefined
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
      // use file extension or Accept header content negotiation
      val uri = getModelUri(r)
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

  def mimeTypeToPair(mimeType: String): Box[(String, String)] = mimeType.split("/") match {
    case Array(superType, subType) => Full((superType, subType))
    case o => Failure("Invalid mime type: " + mimeType)
  }

  def getRequestContentType(r: Req): Box[IContentType] = {
    // use content-type given by "type" parameter
    S.param("type").flatMap(mimeTypeToPair(_)).flatMap(typePair => rdfContentTypes.get(typePair)) or {
      // use Content-Type header
      r.contentType.flatMap(mimeTypeToPair(_)).flatMap(typePair => rdfContentTypes.get(typePair)) or {
        // use file extension if available
        val uri = getModelUri(r)
        if (uri.fileExtension != null) matchTypeByExtension(uri.fileExtension).map(_._2) else None
      }
    }
  }

  def getModelUri(r: Req) = Globals.contextModel.vend.dmap(URIs.createURI(r.hostAndPath + r.uri): URI)(_.getURI)

  def getModel(modelUri: URI) = Globals.contextModelSet.vend flatMap { modelSet =>
    Box.legacyNullTest(modelSet.getModel(modelUri, false)) or {
      if (modelUri.fileExtension != null) Box.legacyNullTest(modelSet.getModel(modelUri.trimFileExtension, false)) else Empty
    }
  }

  protected lazy val RdfGet = new TestGet with RdfTest
  lazy val hasWriter = new QualifiedName(ModelPlugin.PLUGIN_ID, "hasWriter")

  /**
   * Serialize and return RDF data according to the requested content type.
   */
  def serveRdf(r: Req, modelUri: URI) = {
    getModel(modelUri).dmap(Full(new NotFoundResponse("Model " + modelUri + " not found.")): Box[LiftResponse])(model =>
      model match {
        case NotAllowedModel(_) => Full(ForbiddenResponse("You don't have permissions to access " + model.getURI + "."))
        case _ => getResponseContentType(r) map (_.getDefaultDescription) match {
          case Some(cd) if "true".equals(String.valueOf(cd.getProperty(hasWriter))) =>
            val baos = new ByteArrayOutputStream
            model.save(baos, Map(IModel.OPTION_CONTENT_DESCRIPTION -> cd))
            Full(new RdfResponse(baos.toByteArray, cd, Nil, 200))
          case _ => None
        }
      })
  }

  def uploadRdf(r: Req, modelUri: URI, in: InputStream) = {
    getModel(modelUri).dmap(Full(new NotFoundResponse("Model " + modelUri + " not found.")): Box[LiftResponse]) { model =>
      model match {
        case NotAllowedModel(_) => Full(ForbiddenResponse("You don't have permissions to access " + model.getURI + "."))
        case _ => getRequestContentType(r) map (_.getDefaultDescription) match {
          case Full(cd) =>
            model.load(in, Map(IModel.OPTION_CONTENT_DESCRIPTION -> cd));
            // refresh the model
            // model.unloadManager
            Full(OkResponse())
          case _ => None
        }
      }
    }
  }

  def deleteModel(r: Req, modelUri: URI) = {
    getModel(modelUri).dmap(Full(new NotFoundResponse("Model " + modelUri + " not found.")): Box[LiftResponse]) { model =>
      model match {
        case NotAllowedModel(_) => Full(ForbiddenResponse("You don't have permissions to access " + model.getURI + "."))
        case _ =>
          val modelSet = model.getModelSet
          val changeSupport = modelSet.getDataChangeSupport
          try {
            changeSupport.setEnabled(null, false)
            model.getManager.clear
          } finally {
            changeSupport.setEnabled(null, true)
          }
          model.unload
          modelSet.getModels.remove(model)
          modelSet.getMetaDataManager.remove(model)
          Full(OkResponse())
      }
    }
  }

  def validModel(modelName: List[String]) = !modelName.isEmpty && modelName != List("index") || Globals.contextModel.vend.isDefined

  serve {
    case ("vocab" | "models") :: modelName RdfGet req if validModel(modelName) => serveRdf(req, getModelUri(req))
    case ("vocab" | "models") :: modelName Post req => {
      val response = if (validModel(modelName)) {
        val inputStream = req.rawInputStream or {
          req.uploadedFiles.headOption map (_.fileStream)
        }
        inputStream.flatMap { in =>
          try {
            uploadRdf(req, getModelUri(req), in)
          } finally {
            in.close
          }
        }
      } else Full(NotFoundResponse("Unknown model: " + modelName))
      response or Full(BadResponse())
    }
    case ("vocab" | "models") :: modelName Delete req => {
      if (validModel(modelName)) {
        deleteModel(req, getModelUri(req))
      } else Full(NotFoundResponse("Unknown model: " + modelName))
    }
  }
}