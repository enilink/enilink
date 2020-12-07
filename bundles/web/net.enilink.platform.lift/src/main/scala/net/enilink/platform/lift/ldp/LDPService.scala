package net.enilink.platform.lift.ldp

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.URISyntaxException

import scala.jdk.CollectionConverters._
import scala.collection.mutable.ListBuffer

import net.enilink.komma.core.IReference
import net.enilink.komma.core.IStatement
import net.enilink.komma.core.Literal
import net.enilink.komma.core.Namespace
import net.enilink.komma.core.Statement
import net.enilink.komma.core.URI
import net.enilink.komma.core.URIs
import net.enilink.komma.model.IModel
import net.enilink.komma.model.ModelUtil
import net.enilink.platform.lift.util.Globals
import net.enilink.vocab.owl.OWL
import net.enilink.vocab.rdf.RDF
import net.liftweb.common.Box
import net.liftweb.common.Box.option2Box
import net.liftweb.common.BoxOrRaw
import net.liftweb.common.Empty
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.http.LiftResponse
import net.liftweb.http.NotFoundResponse
import net.liftweb.http.OutputStreamResponse
import net.liftweb.http.PlainTextResponse
import net.liftweb.http.Req
import net.liftweb.http.provider.HTTPCookie
import net.liftweb.http.rest.RestHelper

/**
 * Linked Data Platform
 */
object LDPService extends RestHelper {

  val MIME_TURTLE = ("text", "turtle")
  val MIME_JSONLD = ("application", "ld+json")

  val NS_URI = URIs.createURI("http://www.w3.org/ns/ldp#")
  val PROPERTY_CONTAINS = NS_URI.appendLocalPart("contains")
  val TYPE_RESOURCE = NS_URI.appendLocalPart("Resource")
  val TYPE_CONTAINER = NS_URI.appendLocalPart("Container")
  val TYPE_BASICCONTAINER = NS_URI.appendLocalPart("BasicContainer")

  // turtle/json-ld distinction is made by tjSel and using the Convertable in cvt below
  serveTj {
    case Options("ldp" :: refs, req) => getOptions(refs, req)
    case Head("ldp" :: refs, req) => getContainerContent(refs, req)
    case Get("ldp" :: refs, req) => getContainerContent(refs, req)
  }

  trait Convertable {
    def toTurtle: Box[(Int, OutputStream, List[(String, String)])]
    def toJsonLd: Box[(Int, OutputStream, List[(String, String)])]
  }

  def getOptions(refs: List[String], req: Req): Box[Convertable] = {
    Full(new OptionNoContent)
  }
  class OptionNoContent extends Convertable {
    def noContent: Box[(Int, OutputStream, List[(String, String)])] =
      Full(0, new ByteArrayOutputStream, ("Allow" -> "OPTIONS, HEAD, GET") :: Nil)

    override def toTurtle(): Box[(Int, OutputStream, List[(String, String)])] = noContent
    override def toJsonLd(): Box[(Int, OutputStream, List[(String, String)])] = noContent
  }

  def getContainerContent(refs: List[String], req: Req): Box[Convertable] = {
    val requestUri = URIs.createURI(req.request.url)
    val statements: Box[List[IStatement]] = refs match {
      case Nil => Empty
      // FIXME: /ldp/ will return a container with all models in the modelset
      case "index" :: Nil =>
        val stmts = ListBuffer[IStatement]()
        stmts += new Statement(requestUri, RDF.PROPERTY_TYPE, TYPE_RESOURCE)
        stmts += new Statement(requestUri, RDF.PROPERTY_TYPE, TYPE_CONTAINER)
        stmts += new Statement(requestUri, RDF.PROPERTY_TYPE, TYPE_BASICCONTAINER)
        stmts += new Statement(requestUri, URIs.createURI("http://purl.org/dc/terms/title"), new Literal("basic LDP container for all models"))
        // containment triples, get all model URIs
        for (
          modelSet <- Globals.contextModelSet.vend;
          model <- modelSet.getModels.asScala
        ) {
          val modelLdpUri = LdpUri(model.getURI, req)
          stmts += new Statement(requestUri, PROPERTY_CONTAINS, modelLdpUri)
        }
        Full(stmts.toList)

      // FIXME: /ldp/$path[/] will try to use $path as another absolute uri
      case _ =>
        try {
          val resourceUri = (URIs.createURI(req.request.url), req) match { case LdpUri(x) => x }
          val stmts = ListBuffer[IStatement]()
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
                val contentStmts = ListBuffer[IStatement]()
                for (stmt <- query.evaluateRestricted(classOf[IStatement]).iterator.asScala) {
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
    statements match {
      case Full(statements) =>
        // FIXME: get types from all <$requestUri> a <$typeUri> statements
        // these will be added to the response's Link: header
        var types = ListBuffer[IReference]()
        for (stmt <- statements if (stmt.getSubject == requestUri && stmt.getPredicate == RDF.PROPERTY_TYPE))
          types += stmt.getObject.asInstanceOf[IReference]

        Full(new ContainerContent(statements, types.toList))
      case f @ Failure(msg, _, _) => f ~> 500 // something happened, return a 500 status (lift defaults to 404 here)
      case _ => Empty
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
    var candidateUri = uri
    var done = false
    while (!done) {
      result = Globals.contextModelSet.vend.flatMap { ms =>
        // try to get the model from the model set
        try {
          Box.legacyNullTest(ms.getModel(candidateUri, false))
        } catch {
          case e: Exception => Empty
        }
      }
      if (!result.isEmpty)
        done = true
      else if (candidateUri.segmentCount() > 0)
        candidateUri = candidateUri.trimSegments(1)
      // FIXME: for URNs, segments and path-separators do not work, trim the trailing slash, if any
      else if (candidateUri.toString().endsWith("/"))
        candidateUri = URIs.createURI(candidateUri.toString.substring(0, candidateUri.toString.length() - 1))
      else
        done = true
    }
    result
  }

  class ContainerContent(statements: List[IStatement], types: List[IReference]) extends Convertable {

    // these should be rewritten now that ModelUtil is used
    // (both methods do the same, difference is just the mime-type)
    // refactor together with generateResponse
    override def toTurtle(): Box[(Int, OutputStream, List[(String, String)])] = {
      try {
        val output = new ByteArrayOutputStream
        val dataVisitor = ModelUtil.writeData(output, statements.head.getSubject.getURI.toString, MIME_TURTLE._1 + "/" + MIME_TURTLE._2, "UTF-8")
        dataVisitor.visitBegin();
        dataVisitor.visitNamespace(new Namespace("ldp", NS_URI))
        dataVisitor.visitNamespace(new Namespace("dcterms", URIs.createURI("http://purl.org/dc/terms/")))
        for (stmt <- statements) {
          if (stmt.getObject().isInstanceOf[java.lang.String]) {
            dataVisitor.visitStatement(new Statement(stmt.getSubject, stmt.getPredicate, new Literal(stmt.getObject.asInstanceOf[java.lang.String]), stmt.getContext))
          } else {
            dataVisitor.visitStatement(stmt)
          }
        }
        dataVisitor.visitEnd();

        Full(output.size, output, linkHeader)

      } catch {
        case e: Exception => e.printStackTrace(); Failure("Unable to generate " + MIME_TURTLE + ": " + e.getMessage, Full(e), Empty)
      }
    }

    override def toJsonLd(): Box[(Int, OutputStream, List[(String, String)])] = {
      try {
        val output = new ByteArrayOutputStream
        val dataVisitor = ModelUtil.writeData(output, statements.head.getSubject.getURI.toString, MIME_JSONLD._1 + "/" + MIME_JSONLD._2, "UTF-8")
        dataVisitor.visitBegin();
        dataVisitor.visitNamespace(new Namespace("ldp", NS_URI))
        dataVisitor.visitNamespace(new Namespace("dcterms", URIs.createURI("http://purl.org/dc/terms/")))
        for (stmt <- statements) {
          if (stmt.getObject().isInstanceOf[java.lang.String]) {
            dataVisitor.visitStatement(new Statement(stmt.getSubject, stmt.getPredicate, new Literal(stmt.getObject.asInstanceOf[java.lang.String]), stmt.getContext))
          } else {
            dataVisitor.visitStatement(stmt)
          }
        }
        dataVisitor.visitEnd();

        Full(output.size, output, linkHeader)

      } catch {
        case e: Exception => e.printStackTrace(); Failure("Unable to generate " + MIME_JSONLD + ": " + e.getMessage, Full(e), Empty)
      }
    }

    /**
     * Generates the Link header from the list of types.
     */
    def linkHeader() = types match {
      case Nil => Nil
      case _ =>
        var linkRefs = for (typeRef <- types) yield "<" + typeRef.toString + ">; rel='type'"
        ("Link", linkRefs.mkString(", ")) :: Nil
    }
  }

  def generateResponse[T](s: TurtleJsonLdSelect, t: T, r: Req): LiftResponse = {
    println("generating response for " + t)
    t match {
      case c: Convertable => {
        // refactor Convertable's methods
        val (output, contentType) = s match {
          case TurtleSelect => (c.toTurtle, MIME_TURTLE._1 + "/" + MIME_TURTLE._2)
          case JsonLdSelect => (c.toJsonLd, MIME_JSONLD._1 + "/" + MIME_JSONLD._2)
        }
        val (status: Int, size: Int, text: String, headers: List[(String, String)]) = output match {
          case Full((length, stream, types)) => (200, length, stream.toString, types)
          case Failure(msg, _, _) => (500, msg.length, msg, Nil)
          case Empty => (404, 0, "", Nil)
        }

        r.requestType.head_? match {
          case true => new HeadResponse(size, ("Content-Type", contentType) :: ("Allow", "OPTIONS, HEAD, GET") :: headers, Nil, status)
          case false => PlainTextResponse(text, ("Content-Type", contentType) :: ("Allow", "OPTIONS, HEAD, GET") :: headers, status)
        }
      }
      // ATTN: these next two cases don't actually end up here, lift handles them on its own
      case f @ Failure(msg, _, _) => PlainTextResponse("Unable to complete request: " + msg, ("Content-Type", "text/plain") :: Nil, 500)
      case Empty => NotFoundResponse()
    }
  }

  /**
   * Generate the LiftResponse appropriate for the output format from the query result T.
   */
  implicit def cvt[T]: PartialFunction[(TurtleJsonLdSelect, T, Req), LiftResponse] = {
    case (s, t, r: Req) => generateResponse(s, t, r)
  }

  /**
   * Select turtle or json-ld output based on content type preferences.
   */
  implicit def tjSel(req: Req): Box[TurtleJsonLdSelect] = {
    val preferredContentType = req.weightedAccept(0)
    // default is text/turtle, which also wins when quality factor is equal
    // see LDP, section 4.3.2.1, Non-normative note for a description
    if (req.acceptsStarStar || preferredContentType.matches(MIME_TURTLE))
      Full(TurtleSelect)
    else if (preferredContentType.matches(MIME_JSONLD))
      Full(JsonLdSelect)
    else {
      // fallback is also text/turtle
      println("WARN: preferred media type " + preferredContentType + " not supported, using default '" + MIME_TURTLE + "'...")
      Full(TurtleSelect)
    }
  }

  /**
   * Serve a request returning either Turtle or JSON-LD.
   * @see RestHelper#serveJx[T](pf: PartialFunction[Req, BoxOrRaw[T]]): Unit
   */
  protected def serveTj[T](pf: PartialFunction[Req, BoxOrRaw[T]]): Unit = serveType(tjSel)(pf)(cvt)

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

  /**
   * Selection of turtle or json-ld output formats.
   */
  sealed trait TurtleJsonLdSelect

  final case object TurtleSelect extends TurtleJsonLdSelect

  final case object JsonLdSelect extends TurtleJsonLdSelect
}
