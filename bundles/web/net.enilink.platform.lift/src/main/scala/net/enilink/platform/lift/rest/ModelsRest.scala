package net.enilink.platform.lift.rest

import net.enilink.komma.core.URI
import net.enilink.komma.model.{IModel, ModelPlugin}
import net.enilink.platform.lift.util.NotAllowedModel
import net.liftweb.common.{Box, Failure, Full}
import net.liftweb.http.rest.RestHelper
import net.liftweb.http.{BadRequestResponse, ContentType, ForbiddenResponse, InMemoryResponse, LiftResponse, NotFoundResponse, OkResponse, Req, S, UnsupportedMediaTypeResponse}
import org.eclipse.core.runtime.content.{IContentDescription, IContentType}
import org.eclipse.core.runtime.{Platform, QualifiedName}

import java.io.{ByteArrayOutputStream, InputStream}
import scala.jdk.CollectionConverters._

object ModelsRest extends RestHelper {

  import Util._

  /**
   * Simple in-memory response for RDF data.
   */
  case class RdfResponse(data: Array[Byte], contentDescription: IContentDescription, headers: List[(String, String)], code: Int) extends LiftResponse {
    def toResponse = {
      val typeName = contentDescription.getProperty(mimeTypeProp)
      InMemoryResponse(data, ("Content-Length", data.length.toString) :: ("Content-Type", typeName.toString + "; charset=utf-8") :: headers, Nil, code)
    }
  }

  /**
   * If the headers and the suffix say nothing about the
   * response type, should we default to Turtle.  By default,
   * no, override to change the behavior.
   */
  protected def defaultGetAsTurtle: Boolean = false

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
      }
  }.toMap

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
      if ((r.weightedAccept.isEmpty || r.acceptsStarStar) && uri.fileExtension == null && defaultGetAsTurtle) {
        Some(Platform.getContentTypeManager.getContentType("net.enilink.komma.contenttype.turtle"))
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

  lazy val hasWriter = new QualifiedName(ModelPlugin.PLUGIN_ID, "hasWriter")

  /**
   * Serialize and return RDF data according to the requested content type.
   */
  def serveRdf(r: Req, modelUri: URI) = {
    getModel(modelUri).dmap(Full(new NotFoundResponse("Model " + modelUri + " not found.")): Box[LiftResponse]) {
      case model@NotAllowedModel(_) => Full(ForbiddenResponse("You don't have permissions to access " + model.getURI + "."))
      case model => getResponseContentType(r) map (_.getDefaultDescription) match {
        case Some(cd) if "true".equals(String.valueOf(cd.getProperty(hasWriter))) =>
          val baos = new ByteArrayOutputStream
          model.save(baos, Map(IModel.OPTION_CONTENT_DESCRIPTION -> cd).asJava)
          Full(new RdfResponse(baos.toByteArray, cd, Nil, 200))
        case _ => Full(new UnsupportedMediaTypeResponse())
      }
    }
  }

  def uploadRdf(r: Req, modelUri: URI, in: InputStream): Box[LiftResponse] = {
    getOrCreateModel(modelUri) map {
      case NotAllowedModel(_) => ForbiddenResponse("You don't have permissions to access " + modelUri + ".")
      case model => getRequestContentType(r) map (_.getDefaultDescription) match {
        case Full(cd) =>
          model.load(in, Map(IModel.OPTION_CONTENT_DESCRIPTION -> cd).asJava)
          // refresh the model
          // model.unloadManager
          OkResponse()
        case _ => new UnsupportedMediaTypeResponse()
      }
    }
  }

  def clearModel(r: Req, modelUri: URI): Box[LiftResponse] = {
    getModel(modelUri) map {
      case NotAllowedModel(_) => ForbiddenResponse("You don't have permissions to access " + modelUri + ".")
      case model =>
        val modelSet = model.getModelSet
        val changeSupport = modelSet.getDataChangeSupport
        try {
          changeSupport.setEnabled(null, false)
          model.getManager.clear
        } finally {
          changeSupport.setEnabled(null, true)
        }
        OkResponse()
    }
  }

  def deleteModel(r: Req, modelUri: URI) = {
    getModel(modelUri).dmap(Full(new NotFoundResponse("Model " + modelUri + " not found.")): Box[LiftResponse]) {
      case model@NotAllowedModel(_) => Full(ForbiddenResponse("You don't have permissions to access " + model.getURI + "."))
      case model =>
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

  def validModel(modelName: List[String]) = !modelName.isEmpty && modelName != List("index") || S.param("model").isDefined

  serve {
    case ("vocab" | "models") :: modelName Get req if validModel(modelName) => {
      S.param("query") match {
        case Full(sparql) => getSparqlQueryResponseMimeType(req) flatMap { resultMimeType =>
          SparqlRest.queryModel(sparql, getModelUri(req), resultMimeType)
        }
        case _ if getResponseContentType(req).isDefined => serveRdf(req, getModelUri(req))
      }
    }
    case ("vocab" | "models") :: modelName Put req => {
      if (validModel(modelName)) {
        clearModel(req, getModelUri(req)) match {
          case Full(OkResponse()) =>
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
          case other => other
        }
      } else Full(BadRequestResponse())
    }
    case ("vocab" | "models") :: modelName Post req => {
      val response = if (validModel(modelName)) {
        S.param("query") match {
          case Full(sparql) => getSparqlQueryResponseMimeType(req) flatMap { resultMimeType =>
            SparqlRest.queryModel(sparql, getModelUri(req), resultMimeType)
          }
          case _ =>
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
        }
      } else Full(NotFoundResponse("Unknown model: " + modelName.mkString("/")))
      response or Full(BadRequestResponse())
    }
    case ("vocab" | "models") :: modelName Delete req => {
      if (validModel(modelName)) {
        deleteModel(req, getModelUri(req))
      } else Full(NotFoundResponse("Unknown model: " + modelName.mkString("/")))
    }
  }
}
