package net.enilink.platform.ldp;

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Instant
import java.util.Comparator

import scala.collection.JavaConversions.asJavaIterable
import scala.collection.JavaConversions.asScalaSet
import scala.collection.mutable.ListBuffer
import scala.util.Try

import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.rio.Rio

import javax.xml.datatype.XMLGregorianCalendar
import net.enilink.komma.core.IReference
import net.enilink.komma.core.IStatement
import net.enilink.komma.core.Literal
import net.enilink.komma.core.Namespace
import net.enilink.komma.core.Statement
import net.enilink.komma.core.URI
import net.enilink.komma.core.URIs
import net.enilink.komma.em.util.ISparqlConstants
import net.enilink.komma.model.IModel
import net.enilink.komma.model.ModelUtil
import net.enilink.komma.rdf4j.RDF4JValueConverter
import net.enilink.platform.lift.util.Globals
import net.enilink.platform.web.rest.ModelsRest
import net.enilink.vocab.owl.OWL
import net.enilink.vocab.rdf.RDF
import net.enilink.vocab.rdfs.RDFS
import net.enilink.vocab.xmlschema.XMLSCHEMA
import net.liftweb.common.Box
import net.liftweb.common.Box.option2Box
import net.liftweb.common.BoxOrRaw
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
  val OMM_NS = "http://www.w3.org/2005/Incubator/omm/elements/1.0/"
  val OMM = URIs.createURI(OMM_NS)
  val PROP_PROV_GENATTIME = URIs.createURI("http://www.w3.org/ns/prov#generatedAtTime")
  val DCTERMS_RELATION = URIs.createURI("http://purl.org/dc/terms/relation")

  val MIME_TURTLE = ("text", "turtle")
  val MIME_JSONLD = ("application", "ld+json")
  var reqNr = 0
  val notUpdatableP = List(PROP_PROV_GENATTIME, PROP_PROV_GENATTIME, LDP.PROPERTY_MEMBERSHIPRESOURCE, LDP.PROPERTY_HASMEMBERRELATION)
  val notUdaptableO = List(LDP.TYPE_RDFSOURCE, OMM.appendLocalPart("memory"), LDP.TYPE_DIRECTCONTAINER, LDP.TYPE_CONTAINER, LDP.TYPE_BASICCONTAINER)

  // turtle/json-ld distinction is made by tjSel and using the Convertible in cvt below
  // FIXME: support LDP without extra prefix, on requests for plain resource URIs with no other match
  def register(path: String, uri: URI) = serveTj {
    // use backticks to match on path's value
    case Options(`path` :: refs, req) => getOptions(refs, req)
    case Head(`path` :: refs, req) => getContainerContent(refs, req, uri)
    case Get(`path` :: refs, req) => getContainerContent(refs, req, uri)
    case Post(`path` :: refs, req) => { reqNr = reqNr + 1; createContainerContent(refs, req, uri, reqNr) }
    case Put(`path` :: refs, req) => updateContainerContent(refs, req, uri)
    case Delete(`path` :: refs, req) => deleteContainerContent(refs, req, uri)

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
      Full(0, new ByteArrayOutputStream, "", ("Allow" -> "OPTIONS, HEAD, GET, POST, PUT") :: ("Accept-Post" -> "*/*") :: Nil)

    override def toTurtle(): Box[(Int, OutputStream, String, List[(String, String)])] = noContent
    override def toJsonLd(): Box[(Int, OutputStream, String, List[(String, String)])] = noContent
  }
  class UpdateResponse(location: String) extends Convertible {
    def noContent: Box[(Int, OutputStream, String, List[(String, String)])] = {
      val relTypes = ListBuffer[IReference]() ++= List(LDP.TYPE_RESOURCE, LDP.TYPE_DIRECTCONTAINER)
      Full((0, new ByteArrayOutputStream, location, linkHeader(relTypes.sortBy(_.toString))))
    }

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
  protected def updateContainerContent(refs: List[String], req: Req, uri: URI) = Box[Convertible] {
    val response: Box[Convertible] = refs match {
      //user is not allowed to update DOM container directly, but memories and blocks
      //FIXME make graceful and configurable constraints
      case Nil => Failure("412", Empty, Empty)  
      case "index" :: Nil => Failure("412", Empty, Empty)
      case path @ _ =>
        val rdfFormat = Rio.getParserFormatForMIMEType(req.request.contentType.openOr(MIME_TURTLE._1 + "/" + MIME_TURTLE._2))
        if (!rdfFormat.isPresent) Failure("415", Empty, Empty) // unsupported mime
        else if (!req.header("If-Match").isEmpty) {
          val containerContent = getContainerContent(refs, req, uri)
          containerContent match {
            // FIXME PUT only update existing memories, create new memory when it is not exist ?
            case Empty => Empty 
            case Failure(x, y, z) => Failure(x, y, z)
            case Full(content) => {
              val containerContent = content.asInstanceOf[ContainerContent]
              containerContent.generate("text" -> "turtle") match {
                case Empty => Empty
                case Failure(x, y, z) => Failure(x, y, z)
                case Full((size, stream, mime, headers)) => {
                  val sEtag = generateETag(stream)
                  if (sEtag == req.header("If-Match").get) {
                    val resourceUri = URIs.createURI(req.request.url.replace(req.hostAndPath + "/", uri.trimSegments(2).toString))
                    val model = Rio.parse(req.request.inputStream, req.request.url, rdfFormat.get)
                    val valueConverter = new RDF4JValueConverter(SimpleValueFactory.getInstance)
                    val updateStmts = for {
                      statement <- model
                      //skip the new statements that violate container statements
                      if (!notUpdatableP.contains(valueConverter.fromRdf4j(statement.getPredicate))
                          && !notUdaptableO.contains(valueConverter.fromRdf4j(statement.getObject)))
                    } yield statement

                    Globals.contextModelSet.vend.flatMap(ms => {
                      ms.getUnitOfWork.begin()
                      val m = ms.getModel(resourceUri, false)
                      val manager = m.getManager
                      val isMemory = manager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, OMM.appendLocalPart("memory"))
                      manager.remove(resourceUri)

                      try {

                        if (isMemory) {

                          manager.add(new Statement(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_RDFSOURCE))
                          manager.add(new Statement(resourceUri, RDF.PROPERTY_TYPE, OMM.appendLocalPart("memory")))
                          manager.add(new Statement(resourceUri, PROP_PROV_GENATTIME, new Literal(Instant.now.toString, XMLSCHEMA.TYPE_DATETIME)))
                          manager.add(new Statement(resourceUri, LDP.PROPERTY_MEMBERSHIPRESOURCE, resourceUri))
                          manager.add(new Statement(resourceUri, DCTERMS_RELATION, resourceUri.appendLocalPart("relatedResource")))
                          val toc = resourceUri.appendLocalPart("toc").appendSegment("")
                          manager.add(new Statement(toc, RDF.PROPERTY_TYPE, LDP.TYPE_DIRECTCONTAINER))
                          manager.add(new Statement(toc, RDF.PROPERTY_TYPE, LDP.TYPE_CONTAINER))
                          manager.add(new Statement(toc, RDF.PROPERTY_TYPE, LDP.TYPE_RDFSOURCE))
                          manager.add(new Statement(toc, LDP.PROPERTY_HASMEMBERRELATION, OMM.appendLocalPart("element")))
                          manager.add(new Statement(toc, LDP.PROPERTY_MEMBERSHIPRESOURCE, resourceUri))

                        }
                        manager.add(updateStmts.map(smt => {
                          new Statement(
                            valueConverter.fromRdf4j(smt.getSubject()),
                            valueConverter.fromRdf4j(smt.getPredicate()),
                            valueConverter.fromRdf4j(smt.getObject()))
                        }))
                        Full(new UpdateResponse(req.request.url))
                      } catch {
                        case e: Exception => {
                          e.printStackTrace()
                          Failure(e.getMessage, Some(e), Empty)
                        }
                      } finally { ms.getUnitOfWork.end }

                    })
                  } //avoid mid-air update
                  else Failure("412", Empty, Empty)
                }
              }
            }
          }
        } // FIXME 
        else Failure("428", Empty, Empty)

    }
    response match {
      case c @ Full(content) => c
      case f @ Failure(msg, _, _) =>
        //FIXME adding a Link header with rel='http://www.w3.org/ns/ldp#constrainedBy' [RFC5988]
        //to all responses to requests which fail due to violation of those constraints
        Try(msg.toInt).toOption match {
          case Some(code) =>
            code match {
              case 412 => f ~> 412
              case 428 => f ~> 428
              case 415 => f ~> 415
              case 405 => f ~> 405
              case _ => f ~> 500
            }
          case None => f ~> 500 // something happened, return a 500 status (lift defaults to 404 here)
        }

      case _ => Empty
    }

  }
  protected def deleteContainerContent(refs: List[String], req: Req, uri: URI) = Box[Convertible] {
    val response: Box[Convertible] = refs match {
      case Nil => Failure("405", Empty, Empty)
      case "index" :: Nil => Failure("405", Empty, Empty)
      case path @ _ =>
        val resourceUri = URIs.createURI(req.request.url.replace(req.hostAndPath + "/", uri.trimSegments(2).toString))
        Globals.contextModelSet.vend.flatMap(ms => {
          ms.getUnitOfWork.begin
          try {
            val m = ms.getModel(resourceUri, false)
            val manager = m.getManager
            if (manager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, OMM.appendLocalPart("memory"))) {

              val dom = ms.getModel(uri, false)
              dom.getManager.removeRecursive(resourceUri, true)
              val query = manager.createQuery(ISparqlConstants.PREFIX
                + "PREFIX omm: <" + OMM + "> " //
                + "SELECT ?block {" //
                + "  ?memory a omm:memory ; omm:element ?block  . " //
                + "}")
              val blocks = query.evaluate().iterator
              while (blocks.hasNext) {
                val block = blocks.next.asInstanceOf[IReference].getURI
                ms.getModel(block, false).getManager.removeRecursive(block, true)
                manager.remove(block.getURI)
                ModelsRest.deleteModel(null, block)
              }
              manager.removeRecursive(resourceUri, true)

              manager.clearNamespaces()
              ModelsRest.deleteModel(null, resourceUri)

              Full(new UpdateResponse(""))
            } else {
              // delete block

              path.headOption.flatMap(id => {
                val mem = ms.getModel(uri.appendLocalPart(id).appendSegment(""), false)
                mem.getManager.remove(resourceUri)
                manager.removeRecursive(resourceUri, true)
                ModelsRest.deleteModel(null, resourceUri)
                Full(new UpdateResponse(""))
              })

            }

          } catch {
            case e: Exception => {
              e.printStackTrace()
              Failure("404", Empty, Empty)
            }
          } finally { ms.getUnitOfWork.end }
        })
    }

    response match {
      case c @ Full(content) => c
      case f @ Failure(msg, _, _) => {
        Try(msg.toInt).toOption match {
          case Some(405) => f ~> 405
          case Some(404) => f ~> 404
          case None => f ~> 500
        }
      }
      case _ => Empty
    }
  }

  protected def createContainerContent(refs: List[String], req: Req, uri: URI, reqNr: Int) = Box[Convertible] {
    val response: Box[Convertible] = refs match {
      case Nil => Empty
      case "index" :: Nil => {
        def memoryName = (req.header("Slug").openOr("Memory") + reqNr.toString()).toLowerCase().replaceAll("[^a-z0-9-]", "-")
        //          val rdfFormat = {
        //             if (req.request.contentType == "application/ld+json") RDFFormat.JSONLD
        //             else RDFFormat.TURTLE
        //          }
        val containerUri = URIs.createURI(req.request.url.replace(req.hostAndPath + "/", uri.trimSegments(2).toString))
        // val model = Rio.parse(req.request.inputStream,containerUri.toString(), rdfFormat)

        // create memory
        createMemory(memoryName, containerUri)
      }

      case path @ _ => {
        val resourceUri = URIs.createURI(req.request.url.replace(req.hostAndPath + "/", uri.trimSegments(2).toString))
        def blockName = (req.header("Slug").openOr("Block") + reqNr.toString()).toLowerCase().replaceAll("[^a-z0-9-]", "-")
        try {
          path.headOption.flatMap { id =>
            findModel(uri.appendLocalPart(id).appendSegment("")).flatMap { m =>
              createBlock(blockName, m.getURI)
            }
          }

        } catch {
          case e: Exception => {
            e.printStackTrace()
            Failure(e.getMessage, Some(e), Empty)
          }
        }
      }
    }
    response
  }

  def createMemory(memoryName: String, containerUri: URI): Box[Convertible] = {
    Globals.contextModelSet.vend.flatMap(modelSet =>
      {
        modelSet.getUnitOfWork.begin
        try {
          val model = Box.legacyNullTest(modelSet.getModel(containerUri, false)).openOrThrowException("DOM model not initialized")
          val resourceUri = containerUri.appendLocalPart(memoryName).appendSegment("")
          val em = model.getManager
          em.add(new Statement(containerUri, LDP.PROPERTY_CONTAINS, resourceUri))
          val memoryModel = modelSet.createModel(resourceUri)
          memoryModel.setLoaded(true)
          val manager = memoryModel.getManager
          manager.add(new Statement(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_RDFSOURCE))
          manager.add(new Statement(resourceUri, RDF.PROPERTY_TYPE, OMM.appendLocalPart("memory")))
          manager.add(new Statement(resourceUri, PROP_PROV_GENATTIME, new Literal(Instant.now.toString, XMLSCHEMA.TYPE_DATETIME)))
          manager.add(new Statement(resourceUri, LDP.PROPERTY_MEMBERSHIPRESOURCE, resourceUri))
          manager.add(new Statement(resourceUri, DCTERMS_RELATION, resourceUri.appendLocalPart("relatedResource")))
          val toc = resourceUri.appendLocalPart("toc").appendSegment("")
          manager.add(new Statement(toc, RDF.PROPERTY_TYPE, LDP.TYPE_DIRECTCONTAINER))
          manager.add(new Statement(toc, RDF.PROPERTY_TYPE, LDP.TYPE_CONTAINER))
          manager.add(new Statement(toc, RDF.PROPERTY_TYPE, LDP.TYPE_RDFSOURCE))
          manager.add(new Statement(toc, LDP.PROPERTY_HASMEMBERRELATION, OMM.appendLocalPart("element")))
          manager.add(new Statement(toc, LDP.PROPERTY_MEMBERSHIPRESOURCE, resourceUri))
          Full(new UpdateResponse(resourceUri.toString()))
        } catch {
          case t: Throwable => Failure(t.getMessage)
        } finally {
          modelSet.getUnitOfWork.end
        }
      })

  }
  def createBlock(blockName: String, memoryURI: URI): Box[Convertible] = {
    Globals.contextModelSet.vend.flatMap(modelSet =>
      {
        modelSet.getUnitOfWork.begin
        try {
          val model = Box.legacyNullTest(modelSet.getModel(memoryURI, false)).openOrThrowException("DOM model not initialized")
          val blockUri = memoryURI.appendLocalPart(blockName).appendSegment("")
          val em = model.getManager
          em.add(new Statement(memoryURI, OMM.appendLocalPart("element"), blockUri))
          em.add(new Statement(memoryURI.appendLocalPart("toc").appendSegment(""), LDP.PROPERTY_CONTAINS, blockUri))
          val blockModel = modelSet.createModel(blockUri)
          blockModel.setLoaded(true)
          val manager = blockModel.getManager
          manager.add(new Statement(blockUri, RDF.PROPERTY_TYPE, LDP.TYPE_RDFSOURCE))
          manager.add(new Statement(blockUri, RDF.PROPERTY_TYPE, OMM.appendLocalPart("element")))
          manager.add(new Statement(blockUri, PROP_PROV_GENATTIME, new Literal(Instant.now.toString, XMLSCHEMA.TYPE_DATETIME)))
          manager.add(new Statement(blockUri, RDFS.PROPERTY_LABEL, "Memory Block"))
          Full(new UpdateResponse(blockUri.toString()))
        } catch {
          case t: Throwable => Failure(t.getMessage)
        } finally {
          modelSet.getUnitOfWork.end
        }
      })

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

  }
  /**
   * Generates the Link header from the list of types.
   */
  protected def linkHeader(types: Iterable[IReference]) = types match {
    case Nil => Nil
    case _ => ("Link", types.map(t => s"""<${t}>;rel=type""").mkString(", ")) :: Nil
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

          case Full((length, stream, contentTypeOrLocation, types)) => {
            r.requestType.post_? match {
              case true => (201, length, stream.toString, ("Location", contentTypeOrLocation) :: types)
              case false => (200, length, stream.toString, ("Content-Type", contentTypeOrLocation) :: ("Accept-Post", "*/*") :: ("ETag", generateETag(stream)) :: types)
            }
          }
          case Failure(msg, _, _) => (500, msg.length, msg, Nil)

          case Empty => (404, 0, "", Nil)
        }

        r.requestType.head_? match {
          case true => new HeadResponse(size, ("Allow", "OPTIONS, HEAD, GET, POST, PUT, DELETE") :: headers, Nil, status)
          case false => PlainTextResponse(text, ("Allow", "OPTIONS, HEAD, GET, POST, PUT, DELETE") :: headers, status)
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
