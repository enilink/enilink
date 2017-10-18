package net.enilink.web.rest

import java.io.ByteArrayOutputStream

import scala.collection.JavaConversions.asScalaIterator
import scala.collection.JavaConversions.asJavaIterator

import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.query.resultio.QueryResultIO
import org.eclipse.rdf4j.query.resultio.QueryResultWriter
import org.eclipse.rdf4j.rio.WriterConfig
import org.eclipse.rdf4j.rio.helpers.BasicWriterSettings

import net.enilink.commons.iterator.IExtendedIterator
import net.enilink.komma.core.IBindings
import net.enilink.komma.core.IBooleanResult
import net.enilink.komma.core.IGraphResult
import net.enilink.komma.core.ITupleResult
import net.enilink.komma.core.IValue
import net.enilink.komma.core.URI
import net.enilink.komma.model.ModelUtil
import net.enilink.komma.rdf4j.RDF4JValueConverter
import net.enilink.lift.util.NotAllowedModel
import net.liftweb.common.Box
import net.liftweb.common.Full
import net.liftweb.http.BadResponse
import net.liftweb.http.ForbiddenResponse
import net.liftweb.http.InMemoryResponse
import net.liftweb.http.LiftResponse
import net.liftweb.http.NotFoundResponse
import net.liftweb.http.rest.RestHelper
import java.lang.Boolean
import net.enilink.lift.util.Globals
import net.liftweb.http.S
import net.liftweb.util.Helpers.tryo
import net.liftweb.common.Failure
import net.liftweb.http.OkResponse
import net.enilink.komma.model.IModelSet
import net.enilink.komma.core.URIs
import org.eclipse.rdf4j.query.BindingSet
import org.eclipse.rdf4j.query.impl.ListBindingSet

object SparqlRest extends RestHelper {
  import Util._

  val converter = new RDF4JValueConverter(SimpleValueFactory.getInstance)

  def configure(writer: QueryResultWriter) {
    val config = new WriterConfig
    config.useDefaults
    // there is a incompatibility between the Jackson version included in eniLINK and the Jackson version that is required by RDF4J
    // RDF4J expects the class com.fasterxml.jackson.core.util.DefaultPrettyPrinter.Lf2SpacesIndenter to be public
    config.set(BasicWriterSettings.PRETTY_PRINT, Boolean.FALSE)
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

        BadResponse()
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
                  r.iterator.foreach {
                    case bindings: IBindings[_] =>
                      val bindingSet = converter.toRdf4j(bindings)
                      writer.handleSolution(bindingSet)
                    case value: IValue =>
                      // select with only one variable like "select ?s where { ?s ?p ?o }" for which KOMMA returns direct object instances
                      val bindings = new IBindings[IValue] {
                        val values = value :: Nil
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
                  r.iterator.foreach { stmt => writer.visitStatement(stmt) }
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
              case _ => Full(BadResponse()) // unexpected, should not happen
            }
          } else Full(BadResponse())
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
            case _: Exception => Full(BadResponse())
          }
      })
  }

  serve("sparql" :: Nil prefix {
    case Nil Get req => {
      for {
        query <- S.param("query")
        model <- Globals.contextModel.vend
        mimeType <- getSparqlQueryResponseMimeType(req)

        result <- sparqlQuery(query, model.getURI, mimeType)
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