package net.enilink.lift.ldp

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.URISyntaxException

import scala.collection.JavaConversions.asScalaIterator
import scala.collection.JavaConversions.asScalaSet
import scala.collection.mutable.MutableList

import net.enilink.komma.core.IReference
import net.enilink.komma.core.IStatement
import net.enilink.komma.core.Literal
import net.enilink.komma.core.Namespace
import net.enilink.komma.core.Statement
import net.enilink.komma.core.URI
import net.enilink.komma.core.URIs
import net.enilink.komma.model.IModel
import net.enilink.komma.model.ModelUtil
import net.enilink.lift.util.Globals
import net.enilink.lift.util.ContentTypeHelpers
import net.enilink.vocab.owl.OWL
import net.enilink.vocab.rdf.RDF
import net.liftweb.common.Box
import net.liftweb.common.Box.option2Box
import net.liftweb.common.BoxOrRaw
import net.liftweb.common.BoxOrRaw.boxToBoxOrRaw
import net.liftweb.common.Empty
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.http.S
import net.liftweb.http.LiftResponse
import net.liftweb.http.NotFoundResponse
import net.liftweb.http.OutputStreamResponse
import net.liftweb.http.PlainTextResponse
import net.liftweb.http.Req
import net.liftweb.http.provider.HTTPCookie
import net.liftweb.http.rest.RestHelper
import net.liftweb.http.ContentType
import org.eclipse.core.runtime.Platform
import org.eclipse.core.runtime.content.IContentType
import net.liftweb.http.InMemoryResponse
import net.liftweb.http.UnsupportedMediaTypeResponse

/**
 * Linked Data Platform
 */
object LDPService extends RestHelper {
  import ContentTypeHelpers._

  val NS_URI = URIs.createURI("http://www.w3.org/ns/ldp#")
  val PROPERTY_CONTAINS = NS_URI.appendLocalPart("contains")
  val TYPE_RESOURCE = NS_URI.appendLocalPart("Resource")
  val TYPE_CONTAINER = NS_URI.appendLocalPart("Container")
  val TYPE_BASICCONTAINER = NS_URI.appendLocalPart("BasicContainer")

  serve {
    case Options("ldp" :: refs, req) => getOptions(refs, req)
    case Head("ldp" :: refs, req) => getContainerContent(refs, req)
    case Get("ldp" :: refs, req) => getContainerContent(refs, req)
  }

  def getOptions(refs: List[String], req: Req): Box[LiftResponse] = {
    Full(new InMemoryResponse(Array(), ("Allow" -> "OPTIONS, HEAD, GET") :: Nil, S.responseCookies, 200))
  }

  def getContainerContent(refs: List[String], req: Req): Box[LiftResponse] = {
    val requestUri = URIs.createURI(req.request.url)
    lazy val unsupportedMediaTypeResponse: Box[LiftResponse] = Full(UnsupportedMediaTypeResponse())
    responseContentType(req).dmap(unsupportedMediaTypeResponse) { contentType =>
      val stmts = refs match {
        case Nil => Empty
        // FIXME: /ldp/ will return a container with all models in the modelset
        case "index" :: Nil =>
          val stmts = MutableList[IStatement]()
          stmts += new Statement(requestUri, RDF.PROPERTY_TYPE, TYPE_RESOURCE)
          stmts += new Statement(requestUri, RDF.PROPERTY_TYPE, TYPE_CONTAINER)
          stmts += new Statement(requestUri, RDF.PROPERTY_TYPE, TYPE_BASICCONTAINER)
          stmts += new Statement(requestUri, URIs.createURI("http://purl.org/dc/terms/title"), new Literal("basic LDP container for all models"))
          // containment triples, get all model URIs
          for (
            modelSet <- Globals.contextModelSet.vend;
            model <- modelSet.getModels
          ) {
            val modelLdpUri = LdpUri(model.getURI, req)
            stmts += new Statement(requestUri, PROPERTY_CONTAINS, modelLdpUri)
          }
          Full(stmts.toList)

        // FIXME: /ldp/$path[/] will try to use $path as another absolute uri
        case _ =>
          try {
            val resourceUri = (URIs.createURI(req.request.url), req) match { case LdpUri(x) => x }
            val stmts = MutableList[IStatement]()
            // FIXME: if $path has been extracted, add a sameAs relation between request uri and $path
            if (resourceUri != requestUri) stmts += new Statement(requestUri, OWL.PROPERTY_SAMEAS, resourceUri)
            // FIXME: returns a resource for the request uri; no checks are made
            stmts += new Statement(requestUri, RDF.PROPERTY_TYPE, TYPE_RESOURCE)
            // FIXME: with trailing slash, $path is used to find a model from which to query all OWL class instances
            // FIXME: for URNs, URI treats everything after the protocol as opaque, path separator and segments DO NOT WORK
            //if (resourceUri.hasTrailingPathSeparator()) {
            if (resourceUri.toString.endsWith("/")) {
              stmts += new Statement(requestUri, RDF.PROPERTY_TYPE, TYPE_CONTAINER)
              stmts += new Statement(requestUri, RDF.PROPERTY_TYPE, TYPE_BASICCONTAINER)
              stmts += new Statement(requestUri, URIs.createURI("http://purl.org/dc/terms/title"), new Literal("basic LDP container for <" + resourceUri + ">"))
              findModel(resourceUri.getURI) match {
                case Full(model) =>
                  println("querying model " + model + "...")
                  // FIXME: query for something appropriate
                  // currently queries all instances of OWL class and adds those to ldp:contains (pretty-print order, containment first)
                  val query = model.getManager.createQuery("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o . ?s a <" + OWL.TYPE_CLASS + "> }", false)
                  val contentStmts = MutableList[IStatement]()
                  for (stmt <- query.evaluateRestricted(classOf[IStatement])) {
                    if (stmt.getPredicate == RDF.PROPERTY_TYPE && stmt.getObject == OWL.TYPE_CLASS)
                      stmts += new Statement(requestUri, PROPERTY_CONTAINS, stmt.getSubject)
                    contentStmts += stmt
                  }
                  stmts ++= contentStmts
                case _ => Empty
              }
            }
            Full(stmts.toList)
          } catch {
            case e: Exception => {
              e.printStackTrace()
              Failure(e.getMessage, Some(e), Empty)
            }
          }
      }
      stmts.map { stmts =>
        val types = stmts.collect {
          case stmt if (stmt.getSubject == requestUri && stmt.getPredicate == RDF.PROPERTY_TYPE) => stmt.getObject.asInstanceOf[IReference]
        }
        val cd = contentType.getDefaultDescription
        if (isWritable(cd)) {
          toResponse(stmts, mimeType(cd), linkHeader(types), req)
        } else {
          UnsupportedMediaTypeResponse()
        }
      }
    }
  }

  /**
   * Make LDP URLs from some URI or extract some URI from LDP URLs.
   */
  // FIXME: replace 'req.hostAndPath + "/ldp/"'
  object LdpUri {
    def apply(uri: URI, req: Req): URI = {
      uri.toString match {
        case ldpUri if (ldpUri.startsWith(req.hostAndPath + "/ldp/")) => uri
        case otherUri => URIs.createURI(req.hostAndPath + "/ldp/" + otherUri)
      }
    }

    def unapply(b: (URI, Req)): Option[URI] = {
      val (uri, req) = b
      if (!uri.toString.startsWith(req.hostAndPath + "/ldp/"))
        Some(uri)
      else {
        val rest = uri.toString.replaceFirst(req.hostAndPath + "/ldp/", "")
        try {
          val result = URIs.createURI(rest)
          if (result.isRelative()) Some(uri)
          else Some(result)
        } catch {
          case use: URISyntaxException => Some(uri)
        }
      }
    }
  }

  /**
   * Return the model, if any, matching the longest part of the given URI.
   */
  def findModel(uri: URI): Box[IModel] = {
    var result: Box[IModel] = Empty
    var candidate: Box[URI] = Full(uri.trimFragment)

    while (candidate.isDefined) {
      result = for {
        modelUri <- candidate
        ms <- Globals.contextModelSet.vend
        model <- Box !! ms.getModel(modelUri, false)
      } yield model

      candidate = candidate.flatMap { c =>
        if (result.isDefined) Empty
        else if (c.segmentCount > 0) Full(c.trimSegments(1))
        else if (c.toString.endsWith("/")) {
          // FIXME: for URNs, segments and path-separators do not work, trim the trailing slash, if any
          Full(URIs.createURI(c.toString.substring(0, c.toString.length - 1)))
        } else Empty
      }
    }
    result
  }

  /**
   * Write statements according to the given MIME type into an output stream.
   */
  def writeStatements[O <: OutputStream](statements: List[IStatement], mimeType: String, output: O): O = {
    val data = ModelUtil.writeData(output, statements.head.getSubject.getURI.toString, mimeType, "UTF-8")
    data.visitBegin
    data.visitNamespace(new Namespace("ldp", NS_URI))
    data.visitNamespace(new Namespace("dcterms", URIs.createURI("http://purl.org/dc/terms/")))
    for (stmt <- statements) {
      if (stmt.getObject.isInstanceOf[java.lang.String]) {
        data.visitStatement(new Statement(stmt.getSubject, stmt.getPredicate, new Literal(stmt.getObject.asInstanceOf[java.lang.String]), stmt.getContext))
      } else {
        data.visitStatement(stmt)
      }
    }
    data.visitEnd
    output
  }

  /**
   * Generates the Link header from the list of types.
   */
  def linkHeader(types: List[IReference]) = types match {
    case Nil => Nil
    case _ => ("Link", types map { ref => s"<$ref>; rel='type'" } mkString (", ")) :: Nil
  }

  def toResponse(stmts: List[IStatement], contentType: String, headers: List[(String, String)], r: Req): LiftResponse = {
    val data = writeStatements(stmts, contentType, new ByteArrayOutputStream).toByteArray()
    if (r.requestType.head_?) {
      new HeadResponse(data.length, ("Content-Type", contentType) :: ("Allow", "OPTIONS, HEAD, GET") :: headers, S.responseCookies, 200)
    } else {
      InMemoryResponse(data, ("Content-Type", contentType) :: ("Allow", "OPTIONS, HEAD, GET") :: headers, S.responseCookies, 200)
    }
  }

  def responseContentType(r: Req): Box[IContentType] = {
    lazy val turtleType = Platform.getContentTypeManager.getContentType("net.enilink.komma.contenttype.turtle")
    // default is text/turtle, which also wins when quality factor is equal
    // see LDP, section 4.3.2.1, Non-normative note for a description
    if (r.weightedAccept.isEmpty || r.acceptsStarStar) {
      Full(turtleType)
    } else {
      // the default content type is also Turtle
      matchType(r.weightedAccept).map(_._2) or Full(turtleType)
    }
  }

  /**
   * Simple response without content.
   */
  class HeadResponse(size: Long, headers: List[(String, String)], cookies: List[HTTPCookie], code: Int)
      extends OutputStreamResponse({ _.close }, size: Long, headers: List[(String, String)], cookies: List[HTTPCookie], code: Int) {
  }

  /**
   * Extractor for an HTTP HEAD request.
   */
  protected object Head {
    def unapply(r: Req): Option[(List[String], Req)] = if (r.requestType.head_?) Some(r.path.partPath -> r) else None
  }
}
