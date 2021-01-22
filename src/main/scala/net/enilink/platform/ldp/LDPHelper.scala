package net.enilink.platform.ldp;

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Comparator

import scala.collection.JavaConversions.asScalaSet
import scala.collection.mutable.ListBuffer

import javax.xml.datatype.XMLGregorianCalendar
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
import net.enilink.vocab.xmlschema.XMLSCHEMA
import net.liftweb.common.Box
import net.liftweb.common.Box.option2Box
import net.liftweb.common.BoxOrRaw
import net.liftweb.common.BoxOrRaw.boxToBoxOrRaw
import net.liftweb.common.Empty
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.http.ContentType
import net.liftweb.http.LiftResponse
import net.liftweb.http.NotFoundResponse
import net.liftweb.http.OutputStreamResponse
import net.liftweb.http.PlainTextResponse
import net.liftweb.http.Req
import net.liftweb.http.provider.HTTPCookie
import net.liftweb.http.rest.RestHelper

/**
 * Linked Data Platform (LDP) endpoint support.
 * @see http://www.w3.org/TR/ldp/
 */
class LDPHelper extends RestHelper {

  val MIME_TURTLE = ("text", "turtle")
  val MIME_JSONLD = ("application", "ld+json")

  // turtle/json-ld distinction is made by tjSel and using the Convertible in cvt below
  // FIXME: support LDP without extra prefix, on requests for plain resource URIs with no other match
  def register(path: String, uri: URI) = serveTj {
    // use backticks to match on path's value
    case Options(`path` :: refs, req) => getOptions(refs, req)
    case Head(`path` :: refs, req) => getContainerContent(refs, req, uri)
    case Get(`path` :: refs, req) => getContainerContent(refs, req, uri)
  }

  trait Convertible {
    def toTurtle: Box[(Int, OutputStream, String, List[(String, String)])]
    def toJsonLd: Box[(Int, OutputStream, String, List[(String, String)])]
  }

  protected def getOptions(refs: List[String], req: Req): Box[Convertible] = {
    Full(new OptionNoContent)
  }

  class OptionNoContent extends Convertible {
    def noContent: Box[(Int, OutputStream, String, List[(String, String)])] =
      Full(0, new ByteArrayOutputStream, "", ("Allow" -> "OPTIONS, HEAD, GET") :: Nil)

    override def toTurtle(): Box[(Int, OutputStream, String, List[(String, String)])] = noContent
    override def toJsonLd(): Box[(Int, OutputStream, String, List[(String, String)])] = noContent
  }

  protected def getContainerContent(refs: List[String], req: Req, uri: URI): Box[Convertible] = {
    val requestUri = URIs.createURI(req.request.url)
    val content: Box[ContainerContent] = refs match {
      case Nil => Empty
      // FIXME: /DOM/ will return a container with all registered memories
      case "index" :: Nil =>
        findModel(uri).flatMap { m =>
          if (m.getManager.hasMatch(uri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER)) {
            val root = m.getManager.findRestricted(uri, classOf[LdpBasicContainer])
            val content = new ContainerContent(Nil, m.getURI.toString)
            content.addRelType(root.getRelType)
            content ++= root.getTriples(PreferenceHelper.defaultPreferences)
            // FIXME: add a sameAs relation between request uri and root, if not equal
            if (uri != requestUri && !content.isEmpty) {
              content += new Statement(uri, OWL.PROPERTY_SAMEAS, requestUri)
            }
            Full(content)
          } else {
            Empty
          }
        }

      // FIXME: /DOM/$path
      case path @ _ =>
        try {
          val resourceUri = URIs.createURI(req.request.url.replace(req.hostAndPath + "/", uri.trimSegments(2).toString))
          // FIXME: assumption: resource is contained in model using the memory as URI
          path.headOption.flatMap { id =>
            findModel(uri.appendLocalPart(id).appendSegment("")).flatMap { m =>
              println("trying model=" + m + " for resource=" + resourceUri)
              val content = new ContainerContent(Nil, m.getURI.toString)
              if (m.getManager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_DIRECTCONTAINER)) {
                val c = m.getManager.find(resourceUri, classOf[LdpDirectContainer])
                content.addRelType(c.getRelType)
                content ++= c.getTriples(PreferenceHelper.defaultPreferences)
              } else if (m.getManager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER)) {
                val c = m.getManager.find(resourceUri, classOf[LdpBasicContainer])
                content.addRelType(c.getRelType)
                content ++= c.getTriples(PreferenceHelper.defaultPreferences)
              } else if (m.getManager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_RDFSOURCE)) {
                val r = m.getManager.find(resourceUri, classOf[LdpRdfSource])
                content.addRelType(r.getRelType)
                content ++= r.getTriples(PreferenceHelper.defaultPreferences)
              }
              // FIXME: add a sameAs relation between request uri and $path, if not equal
              if (resourceUri != requestUri && !content.isEmpty) {
                content += new Statement(resourceUri, OWL.PROPERTY_SAMEAS, requestUri)
              }
              Full(content)
            }
          }
        } catch {
          case e: Exception => {
            e.printStackTrace()
            Failure(e.getMessage, Some(e), Empty)
          }
        }
    }
    content match {
      case c @ Full(content) => c
      case f @ Failure(msg, _, _) => f ~> 500 // something happened, return a 500 status (lift defaults to 404 here)
      case _ => Empty
    }
  }

  /**
   * Comparator for IStatements that orders according to S, P, O.
   */
  object StatementComparator extends Comparator[IStatement] {
    def compare(s1: IStatement, s2: IStatement) = {
      val sc = s1.getSubject.toString.compareTo(s2.getSubject.toString)
      val pc = s1.getPredicate.toString.compareTo(s2.getPredicate.toString)
      // weight for rdf:type, enforce it being up front
      val pw = if (s1.getPredicate == RDF.PROPERTY_TYPE || s2.getPredicate == RDF.PROPERTY_TYPE) 3 else 1
      val oc = s1.getObject.toString.compareTo(s2.getObject.toString)
      1000000 * sc + 1000 * pc * pw + oc
    }
  }

  /**
   * Return the model, if any, matching the longest part of the given URI.
   */
  protected def findModel(uri: URI): Box[IModel] = {
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

  /**
   * Helper class to output the statements in the requested MIME type.
   */
  class ContainerContent(statements: List[IStatement], baseURI: String) extends Convertible {

    val stmts = ListBuffer[IStatement]() ++= statements.toList
    val relTypes = ListBuffer[IReference]() += LDP.TYPE_RESOURCE

    override def toTurtle(): Box[(Int, OutputStream, String, List[(String, String)])] = generate(MIME_TURTLE)
    override def toJsonLd(): Box[(Int, OutputStream, String, List[(String, String)])] = generate(MIME_JSONLD)

    def +=(st: IStatement) = {
      stmts += st
    }

    def ++=(st: Iterable[IStatement]) = {
      stmts ++= st
    }

    def addRelType(relType: IReference) = {
      relTypes += relType
    }

    def isEmpty = 0 == stmts.size

    def generate(mimeTypePair: (String, String)): Box[(Int, OutputStream, String, List[(String, String)])] = {
      val mimeType = mimeTypePair._1 + "/" + mimeTypePair._2
      try {
        val output = new ByteArrayOutputStream
        val dataVisitor = ModelUtil.writeData(output, baseURI, mimeType, "UTF-8")
        dataVisitor.visitBegin();
        dataVisitor.visitNamespace(new Namespace("ldp", LDP.NAMESPACE_URI))
        dataVisitor.visitNamespace(new Namespace("dcterms", URIs.createURI("http://purl.org/dc/terms/")))
        dataVisitor.visitNamespace(new Namespace("omm", URIs.createURI("http://www.w3.org/2005/Incubator/omm/elements/1.0/")))
        dataVisitor.visitNamespace(new Namespace("prov", URIs.createURI("http://www.w3.org/ns/prov#")))
        stmts.sorted(Ordering.comparatorToOrdering[IStatement](StatementComparator)).foreach { stmt =>
          dataVisitor.visitStatement(convertStatement(stmt))
        }
        dataVisitor.visitEnd
        Full((output.size, output, mimeType, linkHeader(relTypes.sortBy(_.toString))))
      } catch {
        case e: Exception => e.printStackTrace(); Failure("Unable to generate " + mimeType + ": " + e.getMessage, Full(e), Empty)
      }
    }

    protected def convertStatement(stmt: IStatement): IStatement = {
      stmt.getObject match {
        case s: java.lang.String => new Statement(stmt.getSubject, stmt.getPredicate, new Literal(s), stmt.getContext)
        case cal: XMLGregorianCalendar => new Statement(stmt.getSubject, stmt.getPredicate, new Literal(cal.toString, XMLSCHEMA.TYPE_DATETIME), stmt.getContext)
        case _ => stmt
      }
    }

    /**
     * Generates the Link header from the list of types.
     */
    protected def linkHeader(types: Iterable[IReference]) = types match {
      case Nil => Nil
      case _ => ("Link", types.map(t => s"""<${t}>;rel=type""").mkString(", ")) :: Nil
    }
  }

  protected def generateETag(stream: OutputStream): String = {
    val date = System.currentTimeMillis
    "W/\"" + java.lang.Long.toString(date - date % 60000) + "\""
  }

  protected def generateResponse[T](s: TurtleJsonLdSelect, t: T, r: Req): LiftResponse = {
    t match {
      case c: Convertible => {
        // refactor Convertable's methods
        val output = s match {
          case TurtleSelect => c.toTurtle
          case JsonLdSelect => c.toJsonLd
          case DefaultSelect(preferredContentType) =>
            // fallback is also text/turtle
            println("WARN: preferred media type " + preferredContentType + " not supported, using default '" + MIME_TURTLE + "'...")
            c.toTurtle match {
              // FIXME: return it to the browser as text/plain so that it is displayed
              case Full((length, stream, contentType, types)) => Full((length, stream, "text/plain", types))
              case o @ _ => o
            }
        }
        val (status: Int, size: Int, text: String, headers: List[(String, String)]) = output match {
          case Full((length, stream, contentType, types)) => (200, length, stream.toString, ("Content-Type", contentType) :: ("ETag", generateETag(stream)) :: types)
          case Failure(msg, _, _) => (500, msg.length, msg, Nil)
          case Empty => (404, 0, "", Nil)
        }

        r.requestType.head_? match {
          case true => new HeadResponse(size, ("Allow", "OPTIONS, HEAD, GET") :: headers, Nil, status)
          case false => PlainTextResponse(text, ("Allow", "OPTIONS, HEAD, GET") :: headers, status)
        }
      }
      // ATTN: these next two cases don't actually end up here, lift handles them on its own
      case f @ Failure(msg, _, _) => PlainTextResponse("Unable to complete request: " + msg, ("Content-Type", "text/plain") :: Nil, 500)
      case Empty => NotFoundResponse()
    }
  }

  /**
   * Serve a request returning either Turtle or JSON-LD.
   * @see RestHelper#serveJx[T](pf: PartialFunction[Req, BoxOrRaw[T]]): Unit
   */
  protected def serveTj[T](pf: PartialFunction[Req, BoxOrRaw[T]]): Unit = serveType(tjSel)(pf)(cvt)

  /**
   * Generate the LiftResponse appropriate for the output format from the query result T.
   */
  implicit def cvt[T]: PartialFunction[(TurtleJsonLdSelect, T, Req), LiftResponse] = {
    case (s: TurtleJsonLdSelect, t, r: Req) => generateResponse(s, t, r)
  }

  /**
   * Select turtle or json-ld output based on content type preferences.
   */
  implicit def tjSel(req: Req): Box[TurtleJsonLdSelect] = {
    val preferredContentType = req.weightedAccept.headOption.getOrElse(new ContentType(MIME_TURTLE._1, MIME_TURTLE._2, 0, Full(1.0), Nil))
    // default is text/turtle, which also wins when quality factor is equal
    // see LDP, section 4.3.2.1, Non-normative note for a description
    if (req.acceptsStarStar || preferredContentType.matches(MIME_TURTLE))
      Full(TurtleSelect)
    else if (preferredContentType.matches(MIME_JSONLD))
      Full(JsonLdSelect)
    else {
      Full(DefaultSelect(preferredContentType))
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

  /**
   * Selection of turtle or json-ld output formats.
   */
  sealed trait TurtleJsonLdSelect

  final case object TurtleSelect extends TurtleJsonLdSelect

  final case object JsonLdSelect extends TurtleJsonLdSelect

  final case class DefaultSelect(preferred: ContentType) extends TurtleJsonLdSelect
}
