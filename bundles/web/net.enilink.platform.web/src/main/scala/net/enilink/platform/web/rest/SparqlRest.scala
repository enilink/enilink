package net.enilink.platform.web.rest

import net.enilink.komma.core._
import net.enilink.komma.model.{IModel, IModelSet, ModelUtil}
import net.enilink.komma.rdf4j.RDF4JValueConverter
import net.enilink.platform.lift.rest.CorsHelper
import net.enilink.platform.lift.util.{Globals, NotAllowedModel}
import net.liftweb.common.{Box, Full}
import net.liftweb.http.rest.RestHelper
import net.liftweb.http.{InMemoryResponse, LiftResponse, OutputStreamResponse, Req, S}
import net.liftweb.util.Helpers.tryo
import org.eclipse.rdf4j.common.exception.RDF4JException
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.query.{MalformedQueryException, QueryEvaluationException, QueryInterruptedException}
import org.eclipse.rdf4j.query.resultio.{QueryResultIO, QueryResultWriter}
import org.eclipse.rdf4j.rio.WriterConfig
import org.eclipse.rdf4j.rio.helpers.BasicWriterSettings

import java.io.{ByteArrayOutputStream, OutputStream}
import scala.jdk.CollectionConverters._

class SparqlRest extends RestHelper with CorsHelper {

  import net.enilink.platform.web.rest.Util._

  val converter = new RDF4JValueConverter(SimpleValueFactory.getInstance)

  def configure(writer: QueryResultWriter): Unit = {
    val config = new WriterConfig
    config.useDefaults
    // there is a incompatibility between the Jackson version included in eniLINK and the Jackson version that is required by RDF4J
    // RDF4J expects the class com.fasterxml.jackson.core.util.DefaultPrettyPrinter.Lf2SpacesIndenter to be public
    config.set(BasicWriterSettings.PRETTY_PRINT, java.lang.Boolean.FALSE)
    writer.setWriterConfig(config)
  }

  /**
   * Handle SPARQL queries against a model set where a model is NOT specified.
   */
  def sparqlQuery(queryStr: String, readableGraphs: URI, resultMimeType: String): Box[LiftResponse] = {
    Globals.contextModelSet.vend.map { ms =>
      val dmFactory = ms.asInstanceOf[IModelSet.Internal].getDataManagerFactory
      val dm = dmFactory.get
      try {
        val defaultGraphs = S.params("default-graph-uri").flatMap { uriStr => tryo(URIs.createURI(uriStr)) }
        val namedGraphs = S.params("named-graph-uri").flatMap { uriStr => tryo(URIs.createURI(uriStr)) }

        dm.createQuery(queryStr, null, true, defaultGraphs: _*)

        BadRequestResponse()
      } finally {
        dm.close()
      }
    }
  }

  /**
   * Handle SPARQL queries against a model.
   */
  def queryModel(queryStr: String, modelUri: URI, resultMimeType: String): Box[LiftResponse] = {
    getModel(modelUri).dmap(Full(NotFoundResponse("Model " + modelUri + " not found.")): Box[LiftResponse]) {
      case model@NotAllowedModel(_) => Full(ForbiddenResponse("You don't have permissions to access " + model.getURI + "."))
      case model =>
        try {
          val em = model.getManager
          val query = em.createQuery(queryStr)
          // ensure that resources are not instantiated as KOMMA entities
          if ("(?i)(construct\\s*[{]|describe\\s*[?$])".r.findFirstIn(queryStr).isDefined) {
            query.restrictResultType(null.asInstanceOf[String], classOf[IStatement])
          } else {
            query.restrictResultType(null.asInstanceOf[String], classOf[IValue])
          }

          query.evaluate match {
            case r: ITupleResult[_] if QueryResultIO.getWriterFormatForMIMEType(resultMimeType).isPresent =>
              // start unit of work here to avoid closing by loan wrapper
              model.getModelSet.getUnitOfWork.begin()
              val func = (out: OutputStream) => {
                val format = QueryResultIO.getWriterFormatForMIMEType(resultMimeType).get()
                val writer = QueryResultIO.createTupleWriter(format, out)
                configure(writer)

                writer.startQueryResult(r.getBindingNames)

                try {
                  r.iterator.asScala.foreach {
                    case bindings: IBindings[_] =>
                      val bindingSet = converter.toRdf4j(bindings)
                      writer.handleSolution(bindingSet)
                    case value: IValue =>
                      // select with only one variable like "select ?s where { ?s ?p ?o }" for which KOMMA returns direct object instances
                      val values = java.util.List.of(value)
                      val bindings: IBindings[IValue] = new IBindings[IValue] {
                        override def get(key: String): IValue = value

                        override def getKeys: java.util.List[String] = r.getBindingNames

                        override def iterator: java.util.Iterator[IValue] = values.iterator.asInstanceOf[java.util.Iterator[IValue]]
                      }
                      writer.handleSolution(converter.toRdf4j(bindings))
                  }
                } finally {
                  r.close()
                  model.getModelSet.getUnitOfWork.end()
                }

                writer.endQueryResult()
              }

              Full(OutputStreamResponse(func, -1,
                ("Content-Type", resultMimeType + "; charset=utf-8") :: responseHeaders,
                responseCookies, 200))
            case r: IGraphResult =>
              val func = (out: OutputStream) => {
                val writer = ModelUtil.writeData(out, model.getURI.toString, resultMimeType, "UTF-8")
                writer.visitBegin
                try {
                  r.iterator.asScala.foreach { stmt => writer.visitStatement(stmt) }
                } finally {
                  r.close()
                }
                writer.visitEnd
              } : Unit

              Full(OutputStreamResponse(func, -1,
                ("Content-Type", resultMimeType + "; charset=utf-8") :: responseHeaders,
                responseCookies, 200))
            case r: IBooleanResult if QueryResultIO.getBooleanWriterFormatForMIMEType(resultMimeType).isPresent =>
              val baos = new ByteArrayOutputStream
              val format = QueryResultIO.getBooleanWriterFormatForMIMEType(resultMimeType).get()
              val writer = QueryResultIO.createBooleanWriter(format, baos)
              configure(writer)

              try {
                val result = r.asBoolean
                writer.handleBoolean(result)
              } finally {
                r.close()
              }

              val data = baos.toByteArray
              Full(InMemoryResponse(data, ("Content-Length", data.length.toString) ::
                ("Content-Type", resultMimeType + "; charset=utf-8") :: responseHeaders, responseCookies, 200))
            case _ => Full(BadRequestResponse()) // unexpected, should not happen
          }
        } catch {
          case e: Exception => Full(toResponse(e))
        }
    }
  }

  /**
   * Handle SPARQL updates against a model.
   */
  def updateModel(queryStr: String, modelUri: URI, resultMimeType: String): Box[LiftResponse] = {
    getModel(modelUri).dmap(Full(NotFoundResponse("Model " + modelUri + " not found.")): Box[LiftResponse]) {
      case model@NotAllowedModel(_) => Full(ForbiddenResponse("You don't have permissions to access " + model.getURI + "."))
      case model =>
        val em = model.getManager
        val changeSupport = model.getModelSet.getDataChangeSupport
        // disable change support for updates via SPARQL endpoint
        val enabled = changeSupport.isEnabled(null)
        if (enabled) {
          changeSupport.setEnabled(null, false)
        }
        try {
          val update = em.createUpdate(queryStr, model.getURI.toString, true)
          update.execute()
          Full(OkResponse())
        } catch {
          case e: Exception => Full(toResponse(e))
        } finally {
          if (enabled) {
            changeSupport.setEnabled(null, true)
          }
        }
    }
  }

  /**
   * Convert exception to appropriate HTTP response.
   */
  def toResponse(e: Exception): LiftResponse = {
    // find first cause that is instanceof of RDF4JException
    var cause: Throwable = e
    while (cause.getCause != null && !cause.isInstanceOf[RDF4JException]) {
      cause = cause.getCause
    }

    cause match {
      case mqe: MalformedQueryException => plainTextBadRequest("MALFORMED_QUERY: " + mqe.getMessage)
      case _: QueryInterruptedException =>
        plainTextServiceUnavailable("QUERY_TIMEOUT: Query execution exceeded the time limit.")
      case _: QueryEvaluationException =>
        plainTextInternalServerError("QUERY_EVALUATION_ERROR: Query evaluation failed.")
      case _: RDF4JException =>
        plainTextInternalServerError("INTERNAL_ERROR: Internal server error.")
      case _ =>
        plainTextInternalServerError("INTERNAL_ERROR: Internal server error.")
    }
  }

  private def plainTextBadRequest(message: String): LiftResponse = {
    plainTextResponse(400, message)
  }

  private def plainTextNotAcceptable(message: String): LiftResponse = {
    plainTextResponse(406, message)
  }

  private def plainTextServiceUnavailable(message: String): LiftResponse = {
    plainTextResponse(503, message)
  }

  private def plainTextInternalServerError(message: String): LiftResponse = {
    plainTextResponse(500, message)
  }

  private def plainTextResponse(status: Int, message: String): LiftResponse = {
    InMemoryResponse(message.getBytes("UTF-8"), "Content-Type" -> "text/plain; charset=utf-8" :: responseHeaders, responseCookies, status)
  }

  private def missingModelResponse: LiftResponse =
    plainTextBadRequest("MISSING_MODEL: The required parameter 'model' is missing.")

  private def missingQueryResponse: LiftResponse =
    plainTextBadRequest("MISSING_QUERY: The required parameter 'query' is missing.")

  private def invalidQueryRequestResponse: LiftResponse =
    plainTextBadRequest("INVALID_QUERY_REQUEST: The query request structure is invalid.")

  private def modelNotFoundResponse: LiftResponse =
    NotFoundResponse("MODEL_NOT_FOUND: No model found for the given identifier.")

  private def unsupportedAcceptResponse: LiftResponse =
    plainTextNotAcceptable("UNSUPPORTED_ACCEPT: The requested result format is not supported.")

  private def withResponseMimeType(req: Req)(f: String => Box[LiftResponse]): Box[LiftResponse] = {
    getSparqlQueryResponseMimeType(req) match {
      case Full(mimeType) => f(mimeType)
      case _ => Full(unsupportedAcceptResponse)
    }
  }

  private def withRequestedModel(req: Req)(f: IModel => Box[LiftResponse]): Box[LiftResponse] = {
    S.param("model").filter(_.nonEmpty) match {
      case Full(modelName) =>
        try {
          val modelUri = URIs.createURI(modelName)
          if (modelUri.isRelative) {
            Full(modelNotFoundResponse)
          } else {
            getModel(modelUri).dmap(Full(modelNotFoundResponse): Box[LiftResponse]) {
              case model@NotAllowedModel(_) => Full(ForbiddenResponse("You don't have permissions to access " + model.getURI + "."))
              case model => f(model)
            }
          }
        } catch {
          case _: Exception => Full(modelNotFoundResponse)
        }
      case _ => Full(missingModelResponse)
    }
  }

  serve("sparql" :: Nil prefix {
    case Nil Options _ => OkResponse()
    case Nil Get req =>
      S.param("query").filter(_.nonEmpty) match {
        case Full(query) =>
          withRequestedModel(req) { model =>
            withResponseMimeType(req) { mimeType =>
              queryModel(query, model.getURI, mimeType)
            }
          }
        case _ => Full(missingQueryResponse)
      }
    case Nil Post req if S.param("query").isDefined && S.param("update").isDefined =>
      Full(invalidQueryRequestResponse)
    case Nil Post req if S.param("query").isDefined => {
      val query = S.param("query")
      query flatMap { q =>
        withRequestedModel(req) { model =>
          withResponseMimeType(req) { mimeType =>
            queryModel(q, model.getURI, mimeType)
          }
        }
      }
    }
    case Nil Post req if req.contentType.exists(_ == "application/sparql-query") => {
      req.body.filter(_.nonEmpty) match {
        case Full(queryData) =>
          withRequestedModel(req) { model =>
            withResponseMimeType(req) { mimeType =>
              val query = new String(queryData, "UTF-8")
              queryModel(query, model.getURI, mimeType)
            }
          }
        case _ => Full(invalidQueryRequestResponse)
      }
    }
    case Nil Post req if S.param("update").isDefined => {
      val query = S.param("update")
      query flatMap { q =>
        withRequestedModel(req) { model =>
          withResponseMimeType(req) { mimeType =>
            updateModel(q, model.getURI, mimeType)
          }
        }
      }
    }
    case Nil Post req if req.contentType.exists(_ == "application/sparql-update") => {
      req.body.filter(_.nonEmpty) match {
        case Full(queryData) =>
          withRequestedModel(req) { model =>
            withResponseMimeType(req) { mimeType =>
              val query = new String(queryData, "UTF-8")
              updateModel(query, model.getURI, mimeType)
            }
          }
        case _ => Full(invalidQueryRequestResponse)
      }
    }
    case Nil Post _ => Full(invalidQueryRequestResponse)
  })
}