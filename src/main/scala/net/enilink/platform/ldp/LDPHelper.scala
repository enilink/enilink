package net.enilink.platform.ldp

import java.io.{ByteArrayOutputStream, OutputStream}
import java.time.Instant
import java.util.{Comparator, Properties}

import javax.xml.datatype.XMLGregorianCalendar
import net.enilink.komma.core._
import net.enilink.komma.model.{IModel, ModelUtil}
import net.enilink.komma.rdf4j.RDF4JValueConverter
import net.enilink.platform.ldp.config._
import net.enilink.platform.lift.util.Globals
import net.enilink.platform.web.rest.ModelsRest
import net.enilink.vocab.owl.OWL
import net.enilink.vocab.rdf.RDF
import net.enilink.vocab.rdfs.RDFS
import net.enilink.vocab.xmlschema.XMLSCHEMA
import net.liftweb.common.Box.option2Box
import net.liftweb.common._
import net.liftweb.http.provider.HTTPCookie
import net.liftweb.http.rest.RestHelper
import net.liftweb.http.{ContentType, LiftResponse, NotFoundResponse, OutputStreamResponse, PlainTextResponse, Req}
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.rio.Rio

import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._

/**
 * Linked Data Platform (LDP) endpoint support.
 *
 * @see http://www.w3.org/TR/ldp/
 */
class LDPHelper extends RestHelper {

  val DCTERMS = URIs.createURI("http://purl.org/dc/terms")
  val DCTERMS_PROPERTY_CREATED = DCTERMS.appendSegment("created")
  val DCTERMS_PROPERTY_MODIFIED = DCTERMS.appendSegment("modified")
  val DCTERMS_PROPERTY_FORMAT = DCTERMS.appendSegment("format")
  val DCTERMS_PROPERTY_IDENTIFIER = DCTERMS.appendSegment("identifier")
  val DCTERMS_PROPERTY_TITLE = DCTERMS.appendSegment("title")

  val SLUG = "Slug"
  val LINK = "Link"
  val PREFER = "Prefer"
  val DEFAULT_NAME = "resource"

  //FIXME just now for testing
  val constrainedLink = """<https://www.w3.org/TR/ldp/>;rel="http://www.w3.org/ns/ldp#constrainedBy" """
  val MIME_TURTLE = ("text", "turtle")
  val MIME_JSONLD = ("application", "ld+json")
  //modification time threshold in ms. the resource considered remain the same if two modifications take place within this time window
  // FIXME add this parameter to configurations
  val TIME_SLOT = 60000

  val requestCounts = scala.collection.mutable.Map[String, Int]()

  val valueConverter = new RDF4JValueConverter(SimpleValueFactory.getInstance)
  
  // turtle/json-ld distinction is made by tjSel and using the Convertible in cvt below
  // FIXME: support LDP without extra prefix, on requests for plain resource URIs with no other match
  def register(path: String, uri: URI, config: BasicContainerHandler) = {
    serveTj {
      // use backticks to match on path's value
      case Options(`path` :: refs, req) => getOptions(refs, req)
      case Head(`path` :: refs, req) => getContent(refs, req, uri)
      case Get(`path` :: refs, req) => getContent(refs, req, uri)
      case Post(`path` :: refs, req) =>
        val reqNr = requestCounts.put(path, requestCounts.getOrElse(path, 0) + 1)
        createContent(refs, req, uri, reqNr.getOrElse(1), config)
      case Put(`path` :: refs, req) => updateContent(refs, req, uri, config)
      case Delete(`path` :: refs, req) => deleteContent(refs, req, uri, config)
    }
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

  class UpdateResponse(location: String, typ: URI) extends Convertible {
    def noContent: Box[(Int, OutputStream, String, List[(String, String)])] = {
      val relTypes = ListBuffer[IReference]() ++= List(typ)
      val header = if (!location.isEmpty())
        ("Location" -> location) :: linkHeader(relTypes.sortBy(_.toString))
      else linkHeader(relTypes.sortBy(_.toString))
      Full(0, new ByteArrayOutputStream, "", header)
    }
    override def toTurtle(): Box[(Int, OutputStream, String, List[(String, String)])] = noContent
    override def toJsonLd(): Box[(Int, OutputStream, String, List[(String, String)])] = noContent
  }

  case class FailedResponse(code: Int, msg: String, headers: List[(String, String)]) extends Convertible {
    override def toTurtle() = Failure(msg, Empty, Empty)
    override def toJsonLd() = Failure(msg, Empty, Empty)
  }

  // HEAD or GET
  protected def getContent(refs: List[String], req: Req, uri: URI): Box[Convertible] = {
    val requestUri = URIs.createURI(req.request.url)
    val preferences: (Int, String) = req.header(PREFER) match {
      case Empty => (PreferenceHelper.defaultPreferences, "")
      case Full(s) =>
        s.split(";").map(_.trim).toList match {
          case rep :: action :: Nil =>
            val uriToPrefs = Map(
              LDP.PREFERENCE_MINIMALCONTAINER.toString() -> PreferenceHelper.MINIMAL_CONTAINER,
              LDP.PREFERENCE_CONTAINMENT.toString() -> PreferenceHelper.INCLUDE_CONTAINMENT,
              LDP.PREFERENCE_MEMBERSHIP.toString() -> PreferenceHelper.INCLUDE_MEMBERSHIP)
              .withDefaultValue(PreferenceHelper.defaultPreferences())

            action.split("=").map(_.trim).toList match {
              case "include" :: prefs :: Nil =>
                var acc = 0
                prefs.split(" ").map(_.trim).map { p =>
                  acc = acc | uriToPrefs(p.replace("\"", ""))
                }
                if (acc > 0) (acc, rep)
                else (PreferenceHelper.defaultPreferences(), rep)
              case "omit" :: prefs :: Nil =>
                var acc = PreferenceHelper.defaultPreferences()
                 prefs.split(" ").map(_.trim).map(p => acc = acc - uriToPrefs(p.replace("\"", "")))
                 if(acc != 0) (acc, rep)
                 else (PreferenceHelper.MINIMAL_CONTAINER, rep)
              case _ => (PreferenceHelper.defaultPreferences(), rep)
            }
          case _ => (PreferenceHelper.defaultPreferences(), "")
        }
      case _ => (PreferenceHelper.defaultPreferences(), "")
    }

    val content: Box[ContainerContent] = refs match {
      case Nil => Empty
      case "index" :: Nil =>
        findModel(uri).flatMap { m =>
          if (m.getManager.hasMatch(uri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER)) {
            val root = m.getManager.findRestricted(uri, classOf[LdpBasicContainer])
            val content = new ContainerContent(Nil, m.getURI.toString)
            content.addRelType(root.getRelType)
            content ++= root.getTriples(preferences._1).asScala.toIterable
            content.applyPreference(preferences._2)
            // FIXME: add a sameAs relation between request uri and root, if not equal
            if (uri != requestUri && !content.isEmpty) {
              content += new Statement(uri, OWL.PROPERTY_SAMEAS, requestUri)
            }
            Full(content)
          } else {
            Empty
          }
        }

      case path @ _ =>
        try {
          val resourceUri = URIs.createURI(req.request.url.replace(req.hostAndPath + "/", uri.trimSegments(2).toString))
          // FIXME: assumption: resource is contained in model using the same URI
          // path.headOption.flatMap { id =>
          //  findModel(uri.appendLocalPart(id).appendSegment("")).flatMap { m =>
          findModel(resourceUri).flatMap { m =>
            println("trying model=" + m + " for resource=" + resourceUri)
            val content = new ContainerContent(Nil, m.getURI.toString)
            if (m.getManager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_DIRECTCONTAINER)) {
              val c = m.getManager.find(resourceUri, classOf[LdpDirectContainer])
              content.addRelType(c.getRelType)
              content ++= c.getTriples(preferences._1)
              content.applyPreference(preferences._2)
            } else if (m.getManager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER)) {
              val c = m.getManager.find(resourceUri, classOf[LdpBasicContainer])
              content.addRelType(c.getRelType)
              content ++= c.getTriples(preferences._1)
              content.applyPreference(preferences._2)
            } else if (m.getManager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_RDFSOURCE)) {
              val r = m.getManager.find(resourceUri, classOf[LdpRdfSource])
              content.addRelType(r.getRelType)
              content ++= r.getTriples(preferences._1)
              content.applyPreference(preferences._2)
            } else if (m.getManager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_NONRDFSOURCE)) {
              // FIXME: return the binary content from the filestore instead?
              content ++= m.getManager.`match`(resourceUri, null, null).toSet
              content.addRelType(LDP.TYPE_NONRDFSOURCE)
              content.applyPreference(preferences._2)
            }
            // FIXME: add a sameAs relation between request uri and $path, if not equal
            if (resourceUri != requestUri && !content.isEmpty) {
              content += new Statement(resourceUri, OWL.PROPERTY_SAMEAS, requestUri)
            }
            if (content.isEmpty) Empty
            else Full(content)
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
      case _ => Empty ~> 404
    }
  }

  // PUT or PATCH
  protected def updateContent(refs: List[String], req: Req, uri: URI, config: BasicContainerHandler): Box[Convertible] = {

    def headerMatch(requestUri: URI, typ: URI, m: IModel): Box[Boolean] = {
      val ifMatch = req.header("If-Match")
      if (ifMatch.isEmpty) Empty
      else {
        val cEtag = ifMatch.openOr("")
        val res = typ match {
          case LDP.TYPE_BASICCONTAINER => m.getManager.findRestricted(requestUri, classOf[LdpBasicContainer])
          case LDP.TYPE_DIRECTCONTAINER => m.getManager.findRestricted(requestUri, classOf[LdpDirectContainer])
          case LDP.TYPE_RDFSOURCE => m.getManager.findRestricted(requestUri, classOf[LdpRdfSource])
        }
        //FIXME create new Resource (LDP Server may allow the creation of new resources using HTTP PUT
        if (res != null) {
          val containerContent = new ContainerContent(Nil, requestUri.toString())
          containerContent ++= res.getTriples(PreferenceHelper.defaultPreferences())
          containerContent.addRelType(res.getRelType)
          containerContent.generate(MIME_TURTLE) match {
            case Full((size, _, _, _)) =>
              val sEtag = generateETag(size)
              val sEtags = sEtag.split("-")
              val cEtags = cEtag.split("-")
              if (cEtags.length != 2 || sEtags.length != 2) Full(false)
              else if (cEtags(0) == sEtags(0) || cEtags(1) == sEtags(1)) Full(true)
              else Full(false)
            case _ => Full(false)
          }
        } else Full(false)
      }
    }
    val response: Box[Convertible] = refs match {
      //FIXME make graceful and configurable constraints
      case Nil =>
        val typeLink = linkHeader(LDP.TYPE_RDFSOURCE :: Nil)
        val tail = typeLink.head._2 + " ," + constrainedLink
        val msg = s"not recognized as Resource: ${req.uri}, try ${req.uri}/ "
        Full(new FailedResponse(404, msg, ("Link", tail) :: Nil))
      case "index" :: Nil =>
        val typelinks = linkHeader(LDP.TYPE_RESOURCE :: LDP.TYPE_BASICCONTAINER :: Nil)
        val tail = typelinks.head._2 + " ," + constrainedLink
        if (!config.isModifyable())
          Full(new FailedResponse(427, "container configured not modifyable", ("Link", tail) :: Nil))
        else findModel(uri).flatMap(m => {
          val manager = m.getManager
          if (manager.hasMatch(uri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER)) {
            headerMatch(uri, LDP.TYPE_BASICCONTAINER, m) match {
              case Full(true) =>
                getBodyEntity(req, uri.toString()) match {
                  case Left(rdfBody) =>
                    val body = new ReqBodyHelper(rdfBody, uri)
                    //FIXME allow to create it
                    if (!body.isDirectContainer && body.isNoContains) {
                      //replace
                      //FIXME use mapping
                      val configStmts = body.matchConfig(config, uri)
                      manager.removeRecursive(uri, true)
                      //add statements resulting from the configuration
                      manager.add(new Statement(uri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER))
                      configStmts.map(stmt => manager.add(stmt))

                      rdfBody.stream().map(stmt => {
                        val subj = valueConverter.fromRdf4j(stmt.getSubject())
                        val pred = valueConverter.fromRdf4j(stmt.getPredicate())
                        val obj = valueConverter.fromRdf4j(stmt.getObject())
                        if (subj != uri || !body.isServerProperty(pred))
                          manager.add(new Statement(subj, pred, obj))
                      })
                      manager.add(new Statement(uri, DCTERMS_PROPERTY_MODIFIED,
                        new Literal(Instant.now.toString, XMLSCHEMA.TYPE_DATETIME)))

                      Full(new UpdateResponse(uri.toString(), LDP.TYPE_BASICCONTAINER))
                    } else {
                      Full(new FailedResponse(412, "Basic Container should be replaced with basic container", ("Link", tail) :: Nil))
                    }
                  case Right(bin) =>
                    Full(new FailedResponse(412, "Basic Container can not be replaced with binary object", ("Link", tail) :: Nil))
                }
              case Full(false) => Full(new FailedResponse(412, "IF-MATCH Avoiding mid-air collisions ", ("Link", tail) :: Nil))
              case Empty => Full(new FailedResponse(428, "", ("Link", tail) :: Nil))
              case Failure(msg, _, _) => Full(new FailedResponse(428, msg, ("Link", tail) :: Nil))
            }
          } else
            Full(new FailedResponse(412, "root container schould be Basic Container but found another type", ("Link", tail) :: Nil))

        })

      case path @ _ =>
        val typelinks = linkHeader(LDP.TYPE_RESOURCE :: LDP.TYPE_BASICCONTAINER :: Nil)
        val tail = typelinks.head._2 + " ," + constrainedLink
        val resourceUri = URIs.createURI(req.request.url.replace(req.hostAndPath + "/", uri.trimSegments(2).toString))
        val handler = getHandler(path, config)
        if (!handler.isModifyable) Full(new FailedResponse(427, "resource configured not modifyable", ("Link", tail) :: Nil))
        else
          findModel(resourceUri).flatMap(m => {
            val manager = m.getManager
            if (manager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER)) {
              headerMatch(resourceUri, LDP.TYPE_BASICCONTAINER, m) match {
                case Full(true) =>
                  getBodyEntity(req, resourceUri.toString()) match {
                    case Left(rdfBody) =>
                      val body = new ReqBodyHelper(rdfBody, resourceUri)
                      val res = manager.findRestricted(resourceUri, classOf[LdpBasicContainer])
                      if (!body.isDirectContainer && body.isNoContains) {
                        //replace
                        val configStmts = body.matchConfig(handler, resourceUri)
                        manager.removeRecursive(resourceUri, true)
                        manager.add(new Statement(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER))
                        configStmts.map(stmt => manager.add(stmt))
                        rdfBody.asScala.foreach(stmt => {
                          val subj = valueConverter.fromRdf4j(stmt.getSubject())
                          val pred = valueConverter.fromRdf4j(stmt.getPredicate())
                          val obj = valueConverter.fromRdf4j(stmt.getObject())
                          if (subj != resourceUri || !body.isServerProperty(pred))
                            manager.add(new Statement(subj, pred, obj))
                        })
                        manager.add(new Statement(resourceUri, DCTERMS_PROPERTY_MODIFIED,
                          new Literal(Instant.now.toString, XMLSCHEMA.TYPE_DATETIME)))

                        Full(new UpdateResponse(resourceUri.toString(), LDP.TYPE_BASICCONTAINER))
                      } else {
                        Full(new FailedResponse(412, "Basic Container should be replaced with basic container", ("Link", tail) :: Nil))
                      }
                    case Right(bin) =>
                      Full(new FailedResponse(412, "Changing container type not allowed. Basic Container can not be replaced with binary object", ("Link", tail) :: Nil))

                  }
                case Full(false) => Full(new FailedResponse(412, "IF-Match, Avoiding mid-air collisions", ("Link", tail) :: Nil))
                case Empty => Full(new FailedResponse(428, "", ("Link", tail) :: Nil))
                case Failure(msg, _, _) => Full(new FailedResponse(428, msg, ("Link", tail) :: Nil))
              }
            } else if (manager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_DIRECTCONTAINER)) {
              val typelinks = linkHeader(LDP.TYPE_RESOURCE :: LDP.TYPE_DIRECTCONTAINER :: Nil)
              headerMatch(resourceUri, LDP.TYPE_BASICCONTAINER, m) match {
                case Full(true) =>
                  getBodyEntity(req, resourceUri.toString()) match {
                    case Left(rdfBody) =>
                      val body = new ReqBodyHelper(rdfBody, resourceUri)
                      val res = manager.findRestricted(resourceUri, classOf[LdpDirectContainer])
                      if ((body.isDirectContainer || handler.isInstanceOf[DirectContainerHandler]) && !body.isBasicContainer && body.isNoContains) {
                        val memberRel = res.hasMemberRelation()
                        val memberSrc = res.membershipResource()
                        val configStmts = body.matchConfig(handler, resourceUri)
                        manager.removeRecursive(resourceUri, true)
                        manager.add(new Statement(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_DIRECTCONTAINER))
                        // FIXME Direct container should have hasMembership and membershipResource triples only once ?
                        // configuration has priority
                        if (handler.isInstanceOf[DirectContainerHandler]) {
                          res.hasMemberRelation(memberRel)
                          res.membershipResource(memberSrc)
                        }
                        configStmts.map(stmt => manager.add(stmt))

                        rdfBody.asScala.foreach(stmt => {
                          val subj = valueConverter.fromRdf4j(stmt.getSubject())
                          val pred = valueConverter.fromRdf4j(stmt.getPredicate())
                          val obj = valueConverter.fromRdf4j(stmt.getObject())
                          val acceptable = !(subj == resourceUri && body.isServerProperty(pred)) &&
                            !(handler.isInstanceOf[DirectContainerHandler] && (pred == LDP.PROPERTY_HASMEMBERRELATION) || (pred == LDP.PROPERTY_MEMBERSHIPRESOURCE && memberSrc != null))

                          if (acceptable)
                            manager.add(new Statement(subj, pred, obj))
                        })
                        manager.add(new Statement(resourceUri, DCTERMS_PROPERTY_MODIFIED,
                          new Literal(Instant.now.toString, XMLSCHEMA.TYPE_DATETIME)))
                        Full(new UpdateResponse(resourceUri.toString(), LDP.TYPE_DIRECTCONTAINER))
                      } else
                        Full(new FailedResponse(409, "Changing container type not allowed. Direct Container should be replaced with Direct container", ("Link", tail) :: Nil))
                    case Right(bin) =>
                      Full(new FailedResponse(412, "Direct Container should be replaced with Direct container", ("Link", tail) :: Nil))
                  }
                case Full(false) => Full(new FailedResponse(412, "IF-Match, Avoiding mid-air collisions", ("Link", tail) :: Nil))
                case Empty => Full(new FailedResponse(428, "", ("Link", tail) :: Nil))
                case Failure(msg, _, _) => Full(new FailedResponse(428, msg, ("Link", tail) :: Nil))
              }
            } else if (manager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_RDFSOURCE)) {
              val typelinks = linkHeader(LDP.TYPE_RDFSOURCE :: Nil)
              headerMatch(resourceUri, LDP.TYPE_RDFSOURCE, m) match {
                case Full(true) =>
                  getBodyEntity(req, resourceUri.toString()) match {
                    case Left(rdfBody) =>
                      val body = new ReqBodyHelper(rdfBody, resourceUri)
                      val res = manager.findRestricted(resourceUri, classOf[LdpRdfSource])
                      if (!body.isBasicContainer && !body.isDirectContainer) {
                        val configStmts = body.matchConfig(handler, resourceUri)
                        manager.removeRecursive(resourceUri, true)
                        manager.add(new Statement(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_RDFSOURCE))
                        configStmts.map(stmt => manager.add(stmt))
                        rdfBody.asScala.foreach(stmt => {
                          val subj = valueConverter.fromRdf4j(stmt.getSubject())
                          val pred = valueConverter.fromRdf4j(stmt.getPredicate())
                          val obj = valueConverter.fromRdf4j(stmt.getObject())
                          if (subj != resourceUri || !body.isServerProperty(pred))
                            manager.add(new Statement(subj, pred, obj))
                        })
                        manager.add(new Statement(resourceUri, DCTERMS_PROPERTY_MODIFIED,
                          new Literal(Instant.now.toString, XMLSCHEMA.TYPE_DATETIME)))
                        Full(new UpdateResponse(resourceUri.toString(), LDP.TYPE_RDFSOURCE))
                      } else Full(new FailedResponse(412, "resource cannot be replaced, type mismatch ", ("Link", tail) :: Nil))
                    case Right(_) => Full(new FailedResponse(412, "conflict ..", ("Link", tail) :: Nil))
                  }
                case Full(false) => Full(new FailedResponse(412, "IF-Match, Avoiding mid-air collisions", ("Link", tail) :: Nil))
                case Empty => Full(new FailedResponse(428, "", ("Link", tail) :: Nil))
                case Failure(msg, _, _) => Full(new FailedResponse(428, msg, ("Link", tail) :: Nil))
              }
            } else if (manager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_RESOURCE)) {
              Full(new FailedResponse(412, "not supported yet ..", ("Link", tail) :: Nil))
            } else Full(new FailedResponse(412, "not recognized Resource", ("Link", tail) :: Nil))
          })
    }

    response match {
      case c @ Full(content) => c
      case f @ Failure(msg, _, _) => f ~> 500
      case _ => Empty
    }

  }

  // DELETE
  protected def deleteContent(refs: List[String], req: Req, uri: URI, config: BasicContainerHandler): Box[Convertible] = {

    val response: Box[Convertible] = refs match {
      case Nil => Empty
      case "index" :: Nil =>
        findModel(uri).flatMap { m =>
          if (m.getManager.hasMatch(uri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER)) {
            val c = m.getManager.find(uri, classOf[LdpBasicContainer])
            // FIXME should all resources contained in the container to be deleted also be deleted?
            //             c.contains.map( r => {
            //               ModelsRest.deleteModel(null, r.getURI)
            //               m.getManager.removeRecursive(r.getURI, true)
            //             })

            // DELETE on root container is not allowed, but cause to empty it
            // FIXME DE+LETE specification: the sequence DELETE-GET does'nt produce NOT-FOUND response status code
            //Nevertheless DELETE remains idempotent in such implementation
            if (config.isDeletable()) {
              c.contains(new java.util.HashSet)
              Full(new UpdateResponse("", LDP.TYPE_BASICCONTAINER))
            } else
              Full(new FailedResponse(422, "container configured not deletable", ("Link", constrainedLink) :: Nil))
          } else {
            // not supported yet
            Full(new FailedResponse(422, "root container schoud be of type Basic, but found another type", ("Link", constrainedLink) :: Nil))
          }

        }
      case path @ _ => try {

        val requestedUri = URIs.createURI(req.request.url.replace(req.hostAndPath + "/", uri.trimSegments(2).toString))
        findModel(requestedUri).flatMap(m => {
          val manager = m.getManager
          val res = manager.findRestricted(requestedUri, classOf[LdpRdfSource])
          val handler = getHandler(path, config)
          if (handler.isDeletable()) {
            manager.removeRecursive(requestedUri, true)
            if (res.getURI == requestedUri) ModelsRest.deleteModel(null, requestedUri)
            val parent = parentUri(requestedUri)
            if (!parent.isEmpty)
              findModel(parent.get).foreach(m => m.getManager.removeRecursive(requestedUri, true))

            Full(new UpdateResponse("", LDP.TYPE_RESOURCE))
          } else
            Full(new FailedResponse(422, "container configured not deletable", ("Link", constrainedLink) :: Nil))
        })
      } catch {
        case e: Exception => Failure(e.getMessage, Some(e), Empty)
      }
    }

    response match {
      case c @ Full(content) => c
      case f @ Failure(msg, _, _) => f ~> 400
      case _ => Empty
    }
  }

  // POST
  protected def createContent(refs: List[String], req: Req, uri: URI, reqNr: Int, config: BasicContainerHandler): Box[Convertible] = {
    def createResource(model: IModel, containerUri: URI, isRoot: Boolean, conf: RdfResourceHandler) = {
      // attempt to use the slug as is, but append reqNr to avoid duplicates
      val resourceUri = requestedSlug match { case (slug, reqNr) =>
        var candidateUri = containerUri.appendLocalPart(slug)
        if (model.getManager.hasMatch(containerUri, LDP.PROPERTY_CONTAINS, candidateUri)) {
          // FIXME: this might still cause a collision
          candidateUri = containerUri.appendLocalPart(slug + "-" + reqNr)
        }
        if (conf.isSeparateModel || conf.isInstanceOf[ContainerHandler]) {
          candidateUri = candidateUri.appendSegment("")
        }
        candidateUri
      }
      getBodyEntity(req, resourceUri.toString()) match {
        case Left(rdfBody) =>
          val body = new ReqBodyHelper(rdfBody, resourceUri)
          val typ = isRoot match {
            case true => LDP.TYPE_BASICCONTAINER
            case false => resourceType
          }
          val configuredAsBC = conf match {
            case bc: BasicContainerHandler => true
            case _ => false
          }
          val configuredAsDC = conf match {
            case dc: DirectContainerHandler =>
              if (dc.getMembership != null) true
              else false // configuration failure
            case _ => false
          }
          val valid = typ match {
            case LDP.TYPE_BASICCONTAINER => body.isBasicContainer || configuredAsBC
            case LDP.TYPE_DIRECTCONTAINER => (body.isDirectContainer && !body.isBasicContainer) || configuredAsDC
            case LDP.TYPE_RDFSOURCE => !body.isDirectContainer && !body.isBasicContainer
            //TODO add support to indirect containers
            case _ => false
          }

          if (valid) {
            model.getModelSet.getUnitOfWork.begin
            try {
              val resourceModel = conf.isSeparateModel match {
                case false => model
                case true =>
                  val m = model.getModelSet.createModel(resourceUri)
                  m.setLoaded(true)
                  m
              }
              val resourceManager = resourceModel.getManager
              //add server-managed properties
              resourceManager.add(List(
                new Statement(resourceUri, RDF.PROPERTY_TYPE, typ),
                new Statement(resourceUri, DCTERMS_PROPERTY_CREATED, new Literal(Instant.now.toString, XMLSCHEMA.TYPE_DATETIME))).asJava)
              rdfBody.asScala.foreach(stmt => {
                val subj = valueConverter.fromRdf4j(stmt.getSubject())
                val pred = valueConverter.fromRdf4j(stmt.getPredicate())
                val obj = valueConverter.fromRdf4j(stmt.getObject())
                //ignore server-managed properties and configured properties
                val conflict = configuredAsDC && (pred == LDP.PROPERTY_HASMEMBERRELATION || pred == LDP.PROPERTY_MEMBERSHIPRESOURCE)
                if (!(subj == resourceUri && body.isServerProperty(pred)) && !conflict)
                  resourceManager.add(new Statement(subj, pred, obj))
              })
              // handle configuration
              val it = conf.getTypes.iterator
              while (it.hasNext()) resourceManager.add(new Statement(resourceUri, RDF.PROPERTY_TYPE, it.next))
              // if resource to be created was configured to be membership resource for a direct container
              val dh = conf.getDirectContainerHandler
              if (null != dh) {
                // if the new Resource to be created was configured to be Relationship Source
                // for a direct container, this direct container will be also created.
                // it should be no previously created direct containers whose relationship source does
                // not exist(assignable = null or ignored)
                // for this reason the two resources shall be created in the same model
                val dc = resourceUri match {
                  // FIXME: handle URIs with and without trailing '/' differently
                  case _ if (resourceUri.toString().endsWith("/")) => resourceUri.appendLocalPart(dh.getName).appendSegment("")
                  case _ => resourceUri.appendSegment(dh.getName).appendSegment("")
                }
                resourceManager.add(List(
                  new Statement(dc, RDF.PROPERTY_TYPE, LDP.TYPE_DIRECTCONTAINER),
                  new Statement(dc, LDP.PROPERTY_HASMEMBERRELATION, dh.getMembership),
                  new Statement(dc, LDP.PROPERTY_MEMBERSHIPRESOURCE, resourceUri)).asJava)
               
              }
              //if resource was configured to be Direct Container with certain configs take it
              if (conf.isInstanceOf[DirectContainerHandler]) {
                val relHandler = conf.asInstanceOf[DirectContainerHandler]
                if (relHandler.getRelSource != null && relHandler.getRelSource.getAssignedTo != null) {
                  val membershipResource = relHandler.getRelSource.getAssignedTo match {
                    // special case when a container points to itself as resource
                    case _ @self if (DirectContainerHandler.SELF.equals(self)) => resourceUri
                    case _ => relHandler.getRelSource.getAssignedTo
                  }
                  resourceManager.add(List(
                    new Statement(resourceUri, LDP.PROPERTY_HASMEMBERRELATION, relHandler.getMembership),
                    new Statement(resourceUri, LDP.PROPERTY_MEMBERSHIPRESOURCE, membershipResource)).asJava)
                } else println("DirectContainerHandler not configured correctly")
              }

              Full(resourceUri, typ)
            } catch {
              case t: Throwable => Failure(t.getMessage, Some(t), Empty)
            } finally {
              model.getModelSet.getUnitOfWork.end
            }
          } else {
            Failure("invalid or incomplete RDF content")
          }

        case Right(Full(binaryBody)) if binaryBody.length > 0 =>
          Globals.fileStore.make.map { fs =>
            val mimeType = req.contentType.openOr("application/octet-stream")
            val fileName = requestedSlug._1
            val fsKey = fs.store(binaryBody)
            val props = new Properties
            props.setProperty("contentType", mimeType)
            // FileService sends disposition: attachment when the filename is set
            // that causes browsers to prompt for a download when accessing the uri
            // we want images inline for now, so leave the filename out for them
            if (!mimeType.startsWith("image/")) {
              props.setProperty("fileName", fileName)
            }
            fs.setProperties(fsKey, props)
            println("stored binary content type=" + mimeType + " as key=" + fsKey)
            val resourceModel = conf.isSeparateModel match {
              case false => model
              case true =>
                val m = model.getModelSet.createModel(resourceUri)
                m.setLoaded(true)
                m
            }
            val resourceManager = resourceModel.getManager
            //add server-managed properties
            resourceManager.add(List(
              new Statement(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_NONRDFSOURCE),
              new Statement(resourceUri, DCTERMS_PROPERTY_CREATED, new Literal(Instant.now.toString, XMLSCHEMA.TYPE_DATETIME)),
              new Statement(resourceUri, DCTERMS_PROPERTY_IDENTIFIER, URIs.createURI(s"""blobs:$fsKey""")),
              new Statement(resourceUri, DCTERMS_PROPERTY_TITLE, fileName),
              new Statement(resourceUri, RDFS.PROPERTY_LABEL, fileName),
              new Statement(resourceUri, DCTERMS_PROPERTY_FORMAT, mimeType)
            ).asJava)
            Full(resourceUri, LDP.TYPE_NONRDFSOURCE)
          } getOrElse Empty

        case Right(_) => Failure("invalid or empty binary content")
      }
    }

    def requestedSlug = (req.header(SLUG).openOr(DEFAULT_NAME).replaceAll("[^A-Za-z0-9-_.]", "-"), reqNr)
    def requestedUri = URIs.createURI(req.request.url.replace(req.hostAndPath + "/", uri.trimSegments(2).toString))
    //FIXME
    def resourceType = req.header(LINK) match {
      case Full(l) =>
        val t = l.split(";").map(_.trim).apply(0)
        URIs.createURI(t.substring(1, t.length - 1), true)
      case _ => LDP.TYPE_RDFSOURCE
    }
    val response: Box[Convertible] = refs match {
      //root container can only be  basic
      case Nil => Empty
      case "index" :: Nil => {
        findModel(uri).flatMap(m =>
          if (config.isCreatable() && m.getManager.hasMatch(uri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER)) {
            val configuredHandler = config.getContainsHandler
            val handler = if (configuredHandler == null) new RdfResourceHandler
            else configuredHandler
            createResource(m, uri, false, handler) match {
              case Full((resultUri, typ)) =>
                m.getManager.add(new Statement(uri, LDP.PROPERTY_CONTAINS, resultUri))

                Full(new UpdateResponse(resultUri.toString(), typ))
              case Empty =>
                Full(new FailedResponse(415, "not valid body entity or violation of constraints", ("Link", constrainedLink) :: Nil))
              case f: Failure => f
            }
          } else Full(new FailedResponse(412, "root container should be of type basic and configured to be createable", ("Link", constrainedLink) :: Nil)))
      }

      case path @ _ => {
        findModel(requestedUri).flatMap(m => {
          if (m.getManager.hasMatch(requestedUri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER)) {
            val bc = m.getManager.findRestricted(requestedUri, classOf[LdpBasicContainer])
            val conf = getHandler(path, config) match {
              case ch: ContainerHandler => ch
              case _ => println("WARNING: no container handler for req=" + requestedUri); new BasicContainerHandler("")
            }
            val handler = conf.getContainsHandler
            createResource(m, requestedUri, false, handler) match {
              case Full((resultUri, typ)) =>
                m.getManager.add(new Statement(requestedUri, LDP.PROPERTY_CONTAINS, resultUri))
                val res = m.getManager.findRestricted(resultUri, classOf[LdpRdfSource])

                Full(new UpdateResponse(resultUri.toString(), typ))
              case Empty =>
                Full(new FailedResponse(415, "not valid body entity or violation of constraints", ("Link", constrainedLink) :: Nil))
              case f: Failure => f
            }

          } else if (m.getManager.hasMatch(requestedUri, RDF.PROPERTY_TYPE, LDP.TYPE_DIRECTCONTAINER)) {
            val conf = getHandler(path, config) match {
              case dch: DirectContainerHandler => dch
              case _ => println("WARNING: no container handler for req=" + requestedUri); new DirectContainerHandler
            }
            val handler = conf.getContainsHandler
            createResource(m, requestedUri, false, handler) match {
              case Full((resultUri, typ)) =>
                m.getManager.add(new Statement(requestedUri, LDP.PROPERTY_CONTAINS, resultUri))
                val c = m.getManager.findRestricted(requestedUri, classOf[LdpDirectContainer])
                val relationSourceUri = if (c != null) c.membershipResource().getURI
                else {
                  val memSrcConfig = conf.getRelSource
                  if (memSrcConfig != null && memSrcConfig.getAssignedTo != null) memSrcConfig.getAssignedTo
                  else parentUri(requestedUri).get
                }
                val membership = if (c != null) c.hasMemberRelation().getURI
                else conf.getMembership   
                findModel(relationSourceUri) match {
                  case Full(m) => m.getManager.add(new Statement(relationSourceUri, membership, resultUri))
                  case _ =>
                }
                Full(new UpdateResponse(resultUri.toString(), typ))
              case f: Failure =>
                Full(new FailedResponse(415, "not valid body entity or violation of constraints: " + f.msg, ("Link", constrainedLink) :: Nil)) //bad request
              case Empty =>
                Full(new FailedResponse(415, "not valid body entity or violation of constraints", ("Link", constrainedLink) :: Nil)) //bad request
            }
          } // TODO add support for indirect containers
          //LDPR and LDPNR don't accept POST (only containers do )
          else
            Full(new FailedResponse(412, "none container resource shouldn't accept POST request", ("Link", constrainedLink) :: Nil)) //bad request
        })
      }
    }
    response match {
      case c @ Full(content) => c
      case f @ Failure(msg, _, _) => f
      case _ => Empty
    }
  }

  val systemProperties = List(DCTERMS_PROPERTY_CREATED, DCTERMS_PROPERTY_MODIFIED)
  def getBodyEntity(req: Req, base: String): Either[Model, Box[Array[Byte]]] = {
    val rdfFormat = Rio.getParserFormatForMIMEType(
      req.request.contentType.openOr(MIME_TURTLE._1 + "/" + MIME_TURTLE._2))
    if (rdfFormat.isPresent)
      Left(Rio.parse(req.request.inputStream, base, rdfFormat.get))
    else
      Right(req.body)
  }

  class ReqBodyHelper(m: Model, resourceUri: URI) {

    def isResource = m.contains(
      valueConverter.toRdf4j(resourceUri),
      valueConverter.toRdf4j(RDF.PROPERTY_TYPE),
      valueConverter.toRdf4j(LDP.TYPE_RESOURCE))
    def isRdfResource: Boolean = m.contains(
      valueConverter.toRdf4j(resourceUri),
      valueConverter.toRdf4j(RDF.PROPERTY_TYPE),
      valueConverter.toRdf4j(LDP.TYPE_RDFSOURCE))

    def isContainer: Boolean = isRdfResource && m.contains(
      valueConverter.toRdf4j(resourceUri),
      valueConverter.toRdf4j(RDF.PROPERTY_TYPE),
      valueConverter.toRdf4j(LDP.TYPE_CONTAINER)) && isNoContains

    def isBasicContainer = isRdfResource && m.contains(
      valueConverter.toRdf4j(resourceUri),
      valueConverter.toRdf4j(RDF.PROPERTY_TYPE),
      valueConverter.toRdf4j(LDP.TYPE_BASICCONTAINER)) && isNoContains

    def isDirectContainer = isRdfResource && m.contains(
      valueConverter.toRdf4j(resourceUri), valueConverter.toRdf4j(RDF.PROPERTY_TYPE), valueConverter.toRdf4j(LDP.TYPE_DIRECTCONTAINER)) && hasReletionship &&
      (hasRelationshipResource || isMembership) && isNoContains

    def hasRelationshipResource = !m.filter(
      valueConverter.toRdf4j(resourceUri),
      valueConverter.toRdf4j(LDP.PROPERTY_MEMBERSHIPRESOURCE), null).isEmpty

    def isNoContains = m.filter(valueConverter.toRdf4j(resourceUri), valueConverter.toRdf4j(LDP.PROPERTY_CONTAINS), null).isEmpty()

    def hasReletionship = !m.filter(
      valueConverter.toRdf4j(resourceUri),
      valueConverter.toRdf4j(LDP.PROPERTY_HASMEMBERRELATION), null).isEmpty

    def isMembership = !m.filter(
      valueConverter.toRdf4j(resourceUri),
      valueConverter.toRdf4j(LDP.PROPERTY_ISMEMBEROFRELATION), null).isEmpty

    def isServerProperty(prop: IReference) = systemProperties.contains(prop)

    def matchConfig(conf: Handler, resourceUri: URI): Set[IStatement] = {
      val stmts = ListBuffer[IStatement]()
      conf match {
        case c: RdfResourceHandler =>
          val it = c.getTypes.iterator
          while (it.hasNext()) stmts += new Statement(resourceUri, RDF.PROPERTY_TYPE, it.next)
          // FIXME if the resource to be modified is a membershipResource for a DC.
          // should it's elements contained in that dc if any?
          findModel(resourceUri) match {
            case Full(m) =>
              val dh = c.getDirectContainerHandler
              if (dh != null) {
                val res = m.getManager.findRestricted(resourceUri, classOf[LdpRdfSource])
                val it = res.membershipSourceFor.iterator
                while (it.hasNext) {
                  val dc = it.next
                  dc.contains.asScala.foreach(r => stmts += new Statement(resourceUri, dc.hasMemberRelation, r))
                  // special case
                  stmts += new Statement(dc, LDP.PROPERTY_MEMBERSHIPRESOURCE, resourceUri)
                }
              }
              if (c.isInstanceOf[DirectContainerHandler]) {
                val dc = c.asInstanceOf[DirectContainerHandler]
                val res = m.getManager.findRestricted(resourceUri, classOf[LdpDirectContainer])
                val membership = dc.getMembership
                if (membership != null) stmts ++= List(
                  new Statement(resourceUri, LDP.PROPERTY_HASMEMBERRELATION, dc.getMembership),
                  new Statement(resourceUri, LDP.PROPERTY_MEMBERSHIPRESOURCE, res.membershipResource))

              }
            case _ => println("WARNING: no model found for " + resourceUri)
          }
      }
      stmts.toSet
    }
  }

  def getHandler(path: List[String], config: Handler): Handler = {
    if (config == null) new RdfResourceHandler
    else if (path.isEmpty || path.tail.isEmpty) config
    else config match {
      case c: ContainerHandler => getHandler(path.tail, c.getContainsHandler)
      case r: RdfResourceHandler =>
        if (r.getDirectContainerHandler != null) getHandler(path.tail, r.getDirectContainerHandler)
        else new RdfResourceHandler
    }
  }

  def parentUri(requestedUri: URI): Option[URI] =
    if (requestedUri.segmentCount > 1 && requestedUri.toString.endsWith("/"))
      Some(requestedUri.trimSegments(2).appendSegment(""))
    else if (requestedUri.segmentCount > 0)
      Some(requestedUri.trimSegments(1).appendSegment(""))
    else None

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
    var candidateUri = uri.trimFragment()
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
      else if (candidateUri.segmentCount() > 0) {
        // consider
        if (candidateUri.toString.endsWith("/"))
          candidateUri = candidateUri.trimSegments(1)
        else candidateUri = candidateUri.trimSegments(1).appendSegment("")
      } // FIXME: for URNs, segments and path-separators do not work, trim the trailing slash, if any
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
    var preference: String = ""

    override def toTurtle(): Box[(Int, OutputStream, String, List[(String, String)])] = generate(MIME_TURTLE)

    override def toJsonLd(): Box[(Int, OutputStream, String, List[(String, String)])] = generate(MIME_JSONLD)

    def +=(st: IStatement) = {
      stmts += st
    }

    def ++=(st: Iterable[IStatement]) = {
      stmts ++= st
    }

    def ++=(st: java.util.Set[IStatement]) = {
      stmts ++= st.asScala
    }

    def addRelType(relType: IReference) = {
      relTypes += relType
    }

    def applyPreference(pref: String) {
      preference = pref
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
        val headers = linkHeader(relTypes.sortBy(_.toString))
        if (!preference.isEmpty) ("Prefer", preference) :: headers
        Full((output.size, output, mimeType, headers))
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

  /*FIXME weak validator if it is possible for the representation to be modified twice during
   *   TIME_SLOT and retrieved between those modifications.
   *   For now the resource considered as modified if the time window between tow modifications is greater than TIME_SLOT
   *   and result in changing it's size
   */
  protected def generateETag(size: Int): String = {
    val date = System.currentTimeMillis
    "W/\"" + java.lang.Long.toString(date - date % TIME_SLOT) + "-" + size + "\""
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

          case Full((length, stream, contentType, types)) => {
            r.requestType.post_? match {
              case true => (201, length, stream.toString, ("Accept-Post", "*/*") :: types)
              case false => (200, length, stream.toString, ("Content-Type", contentType) :: ("Accept-Post", "*/*") :: ("ETag", generateETag(length)) :: types)
            }
          }
          case Failure(msg, _, _) =>
            c match {
              case FailedResponse(code, msg, links) => (code, msg.length, msg, links)
              case _ => (500, msg.length, msg, Nil)
            }

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
