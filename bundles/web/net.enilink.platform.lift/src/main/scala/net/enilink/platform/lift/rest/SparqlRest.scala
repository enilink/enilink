package net.enilink.platform.lift.rest

import net.enilink.commons.iterator.IExtendedIterator
import net.enilink.komma.core._
import net.enilink.komma.model.{IModelSet, ModelUtil}
import net.enilink.komma.rdf4j.RDF4JValueConverter
import net.enilink.platform.lift.util.{Globals, NotAllowedModel}
import net.liftweb.common.{Box, Full}
import net.liftweb.http.rest.RestHelper
import net.liftweb.http.{BadRequestResponse, ForbiddenResponse, InMemoryResponse, LiftResponse, NotFoundResponse, OkResponse, S}
import net.liftweb.util.Helpers.tryo
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.query.resultio.{QueryResultIO, QueryResultWriter}
import org.eclipse.rdf4j.rio.WriterConfig
import org.eclipse.rdf4j.rio.helpers.BasicWriterSettings

import java.io.ByteArrayOutputStream

object SparqlRest extends RestHelper {

  import Util._

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
        dm.close
      }
    }
  }

  /**
   * Handle SPARQL queries against a model.
   */
  def queryModel(queryStr: String, modelUri: URI, resultMimeType: String): Box[LiftResponse] = {
    getModel(modelUri).dmap(Full(new NotFoundResponse("Model " + modelUri + " not found.")): Box[LiftResponse])(model =>
      model match {
        case NotAllowedModel(_) => Full(ForbiddenResponse("You don't have permissions to access " + model.getURI + "."))
        case _ =>
          val formatOpt = QueryResultIO.getWriterFormatForMIMEType(resultMimeType)
          if (formatOpt.isPresent) {
            val format = formatOpt.get
            val em = model.getManager
            val query = em.createQuery(queryStr)
            query.restrictResultType(null.asInstanceOf[String], classOf[IValue])

            query.evaluate.asInstanceOf[IExtendedIterator[_]] match {
              case r: ITupleResult[_] =>
                val baos = new ByteArrayOutputStream
                val writer = QueryResultIO.createTupleWriter(format, baos)
                configure(writer)

                writer.startQueryResult(r.getBindingNames)

                try {
                  r.iterator.asScala.foreach {
                    case bindings: IBindings[_] =>
                      val bindingSet = converter.toRdf4j(bindings)
                      writer.handleSolution(bindingSet)
                    case value: IValue =>
                      // select with only one variable like "select ?s where { ?s ?p ?o }" for which KOMMA returns direct object instances
                      val bindings = new IBindings[IValue] {
                        val values = List(value).asJava

                        override def get(key: String) = value

                        override def getKeys = r.getBindingNames

                        override def iterator = values.iterator
                      }
                      writer.handleSolution(converter.toRdf4j(bindings))
                  }
                } finally {
                  r.close
                }

                writer.endQueryResult

                val data = baos.toByteArray
                Full(InMemoryResponse(data, ("Content-Length", data.length.toString) :: ("Content-Type", resultMimeType + "; charset=utf-8") :: Nil, Nil, 200))
              case r: IGraphResult =>
                val baos = new ByteArrayOutputStream
                val writer = ModelUtil.writeData(baos, model.getURI.toString, resultMimeType, "UTF-8")

                writer.visitBegin
                try {
                  r.iterator.asScala.foreach { stmt => writer.visitStatement(stmt) }
                } finally {
                  r.close
                }
                writer.visitEnd

                val data = baos.toByteArray
                Full(InMemoryResponse(data, ("Content-Length", data.length.toString) :: ("Content-Type", resultMimeType + "; charset=utf-8") :: Nil, Nil, 200))
              case r: IBooleanResult =>
                val baos = new ByteArrayOutputStream
                val writer = QueryResultIO.createBooleanWriter(format, baos)
                configure(writer)

                try {
                  val result = r.asBoolean
                  writer.handleBoolean(result)
                } finally {
                  r.close
                }

                val data = baos.toByteArray
                Full(InMemoryResponse(data, ("Content-Length", data.length.toString) :: ("Content-Type", resultMimeType + "; charset=utf-8") :: Nil, Nil, 200))
              case _ => Full(BadRequestResponse()) // unexpected, should not happen
            }
          } else Full(BadRequestResponse())
      })
  }

  /**
   * Handle SPARQL updates against a model.
   */
  def updateModel(queryStr: String, modelUri: URI, resultMimeType: String): Box[LiftResponse] = {
    getModel(modelUri).dmap(Full(new NotFoundResponse("Model " + modelUri + " not found.")): Box[LiftResponse])(model =>
      model match {
        case NotAllowedModel(_) => Full(ForbiddenResponse("You don't have permissions to access " + model.getURI + "."))
        case _ =>
          val em = model.getManager
          try {
            val update = em.createUpdate(queryStr, model.getURI.toString, true)
            update.execute
            Full(OkResponse())
          } catch {
            case _: Exception => Full(BadRequestResponse())
          }
      })
  }

  serve("sparql" :: Nil prefix {
    case Nil Get req => {
      for {
        query <- S.param("query")
        model <- Globals.contextModel.vend
        mimeType <- getSparqlQueryResponseMimeType(req)

        result <- queryModel(query, model.getURI, mimeType)
      } yield result
    }
    case Nil Post req if S.param("query").isDefined => {
      val query = S.param("query")
      query flatMap { q =>
        for {
          model <- Globals.contextModel.vend
          mimeType <- getSparqlQueryResponseMimeType(req)

          result <- queryModel(q, model.getURI, mimeType)
        } yield result
      }
    }
    case Nil Post req if req.contentType.exists(_ == "application/sparql-query") => {
      for {
        mimeType <- getSparqlQueryResponseMimeType(req)
        queryData <- req.body
        query = new String(queryData, "UTF-8")
        model <- Globals.contextModel.vend

        result <- queryModel(query, model.getURI, mimeType)
      } yield result
    }
    case Nil Post req if S.param("update").isDefined => {
      val query = S.param("update")
      query flatMap { q =>
        for {
          model <- Globals.contextModel.vend
          mimeType <- getSparqlQueryResponseMimeType(req)

          result <- updateModel(q, model.getURI, mimeType)
        } yield result
      }
    }
    case Nil Post req if req.contentType.exists(_ == "application/sparql-update") => {
      for {
        mimeType <- getSparqlQueryResponseMimeType(req)
        queryData <- req.body
        query = new String(queryData, "UTF-8")
        model <- Globals.contextModel.vend

        result <- updateModel(query, model.getURI, mimeType)
      } yield result
    }
  })
}
