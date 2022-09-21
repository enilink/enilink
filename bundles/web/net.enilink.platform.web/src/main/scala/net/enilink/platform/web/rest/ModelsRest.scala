package net.enilink.platform.web.rest

import net.enilink.komma.core.{KommaException, URI, URIs}
import net.enilink.komma.model.{IModel, ModelPlugin}
import net.enilink.platform.lift.rest.CorsHelper
import net.enilink.platform.lift.util.NotAllowedModel
import net.liftweb.common.Box.{box2Option, option2Box}
import net.liftweb.common.{Box, Empty, Failure, Full}
import net.liftweb.http.rest.RestHelper
import net.liftweb.http.{ContentType, InMemoryResponse, LiftResponse, Req, S}
import org.eclipse.core.runtime.content.{IContentDescription, IContentType}
import org.eclipse.core.runtime.{Platform, QualifiedName}

import java.io.{ByteArrayOutputStream, IOException, InputStream}
import scala.jdk.CollectionConverters._

class ModelsRest extends RestHelper with CorsHelper {

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
  // Platform.getContentTypeManager may be null if OSGi is not running
  lazy val rdfContentTypes: Map[(String, String), IContentType] = Option(Platform.getContentTypeManager).toList
    .flatMap(_.getAllContentTypes()).flatMap {
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
  def matchType(requestedTypes: List[ContentType]): Option[((String, String), IContentType)] = {
    object FindContentType {
      // extractor for partial function below
      def unapply(ct: ContentType): Option[((String, String), IContentType)] = rdfContentTypes.find(e => ct.matches(e._1))
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
  def serveRdf(r: Req, modelUri: URI): Box[LiftResponse] = {
    getModel(modelUri).dmap(Full(NotFoundResponse("Model " + modelUri + " not found.")): Box[LiftResponse]) {
      case model@NotAllowedModel(_) => Full(ForbiddenResponse("You don't have permissions to access " + model.getURI + "."))
      case model => getResponseContentType(r) map (_.getDefaultDescription) match {
        case Some(cd) if "true".equals(String.valueOf(cd.getProperty(hasWriter))) =>
          val baos = new ByteArrayOutputStream
          model.save(baos, Map(IModel.OPTION_CONTENT_DESCRIPTION -> cd).asJava)
          Full(RdfResponse(baos.toByteArray, cd, responseHeaders, 200))
        case _ => Full(UnsupportedMediaTypeResponse())
      }
    }
  }

  def uploadRdf(r: Req, modelUri: URI, contentDescription: IContentDescription, in: InputStream): Box[LiftResponse] = {
    getOrCreateModel(modelUri) map {
      case NotAllowedModel(_) => ForbiddenResponse("You don't have permissions to access " + modelUri + ".")
      case model =>
        try {
          model.load(in, Map(IModel.OPTION_CONTENT_DESCRIPTION -> contentDescription).asJava)
          // refresh the model
          // model.unloadManager
          OkResponse()
        } catch {
          case ke : KommaException => BadRequestResponse(ke.getMessage)
          // this is likely some problem that is not related to the data
          case ioe : IOException => InternalServerErrorResponse()
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

  def deleteModel(r: Req, modelUri: URI): Box[LiftResponse] = {
    getModel(modelUri).dmap(Full(NotFoundResponse("Model " + modelUri + " not found.")): Box[LiftResponse]) {
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

  def validModel(modelName: List[String]): Boolean = !modelName.isEmpty && modelName != List("index") || S.param("model").isDefined

  serve {
    case ("vocab" | "models") :: _ Options _ => OkResponse()
    case ("vocab" | "models") :: modelName Get req if validModel(modelName) => {
      S.param("query") match {
        case Full(sparql) => getSparqlQueryResponseMimeType(req) flatMap { resultMimeType =>
          SparqlRest.queryModel(sparql, getModelUri(req), resultMimeType)
        }
        case _ if getResponseContentType(req).isDefined => serveRdf(req, getModelUri(req))
        case _ => BadRequestResponse()
      }
    }
    case ("vocab" | "models") :: modelName Put req => {
      if (validModel(modelName)) {
        clearModel(req, getModelUri(req)) match {
          case Full(OkResponse()) =>
            getRequestContentType(req) map (_.getDefaultDescription) match {
              case Full(cd) =>
                val inputStream = req.rawInputStream or {
                  req.uploadedFiles.headOption map (_.fileStream)
                }
                inputStream.flatMap { in =>
                  try {
                    uploadRdf(req, getModelUri(req), cd, in)
                  } finally {
                    in.close
                  }
                }
              case _ => UnsupportedMediaTypeResponse()
            }
          case other => other
        }
      } else Full(BadRequestResponse("Invalid model"))
    }
    case ("vocab" | "models") :: modelName Post req =>
      val response: Box[LiftResponse] = if (validModel(modelName)) {
        S.param("query") match {
          case Full(sparql) => getSparqlQueryResponseMimeType(req) flatMap { resultMimeType =>
            SparqlRest.queryModel(sparql, getModelUri(req), resultMimeType)
          }
          case _ =>
            req.rawInputStream match {
              case Full(in) =>
                // this is a standard request
                try {
                  getRequestContentType(req) match {
                    case Full(contentType) => uploadRdf(req, getModelUri(req), contentType.getDefaultDescription, in)
                    case _ => Full(UnsupportedMediaTypeResponse())
                  }
                } finally {
                  in.close()
                }
              case _ =>
                // this is a multipart/form-data request
                val modelUri = getModelUri(req)
                getOrCreateModel(modelUri) map {
                  case NotAllowedModel(_) => ForbiddenResponse("You don't have permissions to access " + modelUri + ".")
                  case model =>
                    // individually upload each file but stop at first error
                    req.uploadedFiles.foldLeft(Empty: Box[LiftResponse]) { case (prevErrorResponse, f) =>
                      // upload stops with first error
                      prevErrorResponse or {
                        // use Content-Type header
                        val contentTypeBox = mimeTypeToPair(f.mimeType).flatMap(rdfContentTypes.get(_)) or {
                          // use file extension if available
                          val uri = URIs.createURI(f.fileName)
                          if (uri.fileExtension != null) matchTypeByExtension(uri.fileExtension).map(_._2) else Empty
                        }
                        contentTypeBox match {
                          case Full(contentType) =>
                            val in = f.fileStream
                            try {
                              model.load(in, Map(IModel.OPTION_CONTENT_DESCRIPTION -> contentType.getDefaultDescription).asJava)
                            } catch {
                              case e: IOException => Full(InternalServerErrorResponse())
                            } finally {
                              in.close()
                            }
                            prevErrorResponse
                          case _ => Full(UnsupportedMediaTypeResponse())
                        }
                      }
                    } openOr OkResponse()
                }
            }
        }
      } else Full(BadRequestResponse("Invalid model"))
      response or Full(BadRequestResponse())
    case ("vocab" | "models") :: modelName Delete req => {
      if (validModel(modelName)) {
        deleteModel(req, getModelUri(req))
      } else Full(BadRequestResponse("Invalid model"))
    }
  }
}