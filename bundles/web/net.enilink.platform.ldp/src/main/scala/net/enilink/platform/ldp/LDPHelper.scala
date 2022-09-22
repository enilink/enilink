package net.enilink.platform.ldp

import java.io.{ByteArrayOutputStream, OutputStream}
import java.time.Instant
import java.util.{Comparator, Properties}

import javax.xml.datatype.XMLGregorianCalendar
import net.enilink.komma.core._
import net.enilink.komma.model.{IModel, ModelUtil}
import net.enilink.platform.ldp.config._
import net.enilink.platform.ldp.impl.OperationResponse
import net.enilink.platform.lift.util.Globals
import net.enilink.platform.web.rest.ModelsRest
import net.enilink.vocab.owl.OWL
import net.enilink.vocab.rdf.RDF
import net.enilink.vocab.rdfs.RDFS
import net.enilink.vocab.xmlschema.XMLSCHEMA
import net.liftweb.common.Box.option2Box
import net.liftweb.common.{Box, _}
import net.liftweb.http.provider.HTTPCookie
import net.liftweb.http.rest.RestHelper
import net.liftweb.http.{ContentType, LiftResponse, NotFoundResponse, OutputStreamResponse, PlainTextResponse, Req}
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.rio.Rio

import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._

/**
 * Linked Data Platform (LDP) endpoint support.
 *
 * @see http://www.w3.org/TR/ldp/
 */
class LDPHelper extends RestHelper {

  val SLUG = "Slug"
  val LINK = "Link"
  val PREFER = "Prefer"
  val DEFAULT_NAME = "resource"

  //FIXME make this configurable as error pages
  val constrainedLink = """<https://www.w3.org/TR/ldp/>;rel="http://www.w3.org/ns/ldp#constrainedBy" """
  val constraintHeader =("Link", constrainedLink) :: Nil
  val MIME_TURTLE = ("text", "turtle")
  val MIME_JSONLD = ("application", "ld+json")
  //modification time threshold in ms. the resource considered remain the same if two modifications take place within this time window
  // FIXME add this parameter to configurations
  val TIME_SLOT = 60000

  val requestCounts = scala.collection.mutable.Map[String, Int]()
  var reqNr: Int = 0; //LDP servers that allow member creation via POST SHOULD NOT re-use URIs (also if resource deleted).
  var createNewFromPut  = false // to know if the new created resource frpm POST or PUT

  // turtle/json-ld distinction is made by tjSel and using the Convertible in cvt below
  // FIXME: support LDP without extra prefix, on requests for plain resource URIs with no other match
  def register(path: String, uri: URI, config: BasicContainerHandler) = {
    val handler: BasicContainerHandler = if (null != config) config else new BasicContainerHandler(path);
    serveTj {
      // use backticks to match on path's value
      case Options(`path` :: refs, req) => getOptions(refs, req)
      case Head(`path` :: refs, req) => getContent(refs, req, uri)
      case Get(`path` :: refs, req) =>  getContent(refs, req, uri)
      case Post(`path` :: refs, req) => createNewFromPut = false; createContent(refs, req, uri, handler, fromPut = false)
      case Patch(`path` :: refs, req) => updateContent(refs, req, uri, handler, partial = true)
      case Put(`path` :: refs, req) => updateContent(refs, req, uri, handler, partial = false)
      case Delete(`path` :: refs, req) => deleteContent(refs, req, uri,handler)
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
      Full(0, new ByteArrayOutputStream, "", ("Allow" -> "OPTIONS, HEAD, GET, POST, PUT, PATCH") :: ("Accept-Post" -> "*/*") :: ("Accept-Patch" -> "text/ldpatch") :: Nil)

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
    override def toTurtle() = Failure(msg, Empty, Empty) : Box[(Int, OutputStream, String, List[(String, String)])]

    override def toJsonLd() = Failure(msg, Empty, Empty) : Box[(Int, OutputStream, String, List[(String, String)])]
  }

  def unrecognizedResource(resourceUri: String): Box[Convertible] = {
    val msg = s"not recognized as Resource: ${resourceUri}, try ${resourceUri}/ "
    Full(FailedResponse(404, msg, computeLinkHeader(LDP.TYPE_RDFSOURCE :: Nil)))
  }

  def updateRoot(req: Req, uri: URI, config: BasicContainerHandler, partial: Boolean): Box[Convertible] = {
    val typelinks = computeLinkHeader(LDP.TYPE_RESOURCE :: LDP.TYPE_BASICCONTAINER :: Nil)
    if (!config.isModifyable())
      Full(FailedResponse(428, "container configured not modifiable", typelinks))
    else findModel(uri).flatMap(m => {
      val manager = m.getManager
      if (manager.hasMatch(uri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER)) {
        partial match {
          case false =>
            update(req,typelinks,uri,LDP.TYPE_BASICCONTAINER,m,config) match {
              case Full(opRes: OperationResponse) if !opRes.hasError => Full(new UpdateResponse(uri.toString(), LDP.TYPE_BASICCONTAINER))
              case Full(opRes: OperationResponse) if opRes.hasError => Full(FailedResponse(opRes.code(), opRes.msg(), typelinks))
              case Empty => Full(FailedResponse(OperationResponse.IF_MATCH_MISSING, "Missing If-Mach Header (avoid mid-air change ", typelinks))
            }
          case true => patchUpdate(req, typelinks, uri, LDP.TYPE_BASICCONTAINER, m, config)
        }
      } else
        Full( FailedResponse(OperationResponse.PRECONDITION_FAILED, "root container should be Basic Container but found another type", typelinks))
    })
  }

  //confirm specifications to allow creating new resource with PUT for certain cases
  def computeResourceType( resourceUri: URI, req: Req): Box[(URI, List[(String, String)], IModel)] = {
    findModel(resourceUri).flatMap { m =>
      val manager = m.getManager
      var typelinks: List[(String, String)] = Nil
      var typ: URI = null
      if (manager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER)) {
        typelinks = computeLinkHeader(LDP.TYPE_RESOURCE :: LDP.TYPE_BASICCONTAINER :: Nil)
        typ =  LDP.TYPE_BASICCONTAINER
      } else if (manager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_DIRECTCONTAINER)) {
        typelinks = computeLinkHeader(LDP.TYPE_RESOURCE :: LDP.TYPE_DIRECTCONTAINER :: Nil)
        typ = LDP.TYPE_DIRECTCONTAINER
      } else if (manager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_RDFSOURCE)) {
        typelinks = computeLinkHeader(LDP.TYPE_RDFSOURCE :: Nil)
        typ = LDP.TYPE_RDFSOURCE
      } else if (manager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_NONRDFSOURCE)) {
        // FIXME: is configuration model here also needed ?
        typelinks = computeLinkHeader(LDP.TYPE_RESOURCE :: LDP.TYPE_NONRDFSOURCE :: Nil)
        typ = LDP.TYPE_NONRDFSOURCE
      }
      typ match {
        case null => Empty
        case _ => Full((typ, typelinks, m))
      }
    }
  }

  // HEAD or GET
  protected def getContent(refs: List[String], req: Req, uri: URI): Box[Convertible] = {
    val requestUri = URIs.createURI(req.request.url)
    val preferences = req.header(PREFER)
    val content: Box[ContainerContent] = refs match {
      case Nil => Empty
      case "index" :: Nil =>
        findModel(uri).flatMap { m =>
          if (m.getManager.hasMatch(uri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER)) {
            val content = getContainerContent(uri, classOf[LdpBasicContainer], m, preferences)
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
          findModel(resourceUri).flatMap { m =>
            var content: ContainerContent = null
            if (m.getManager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_DIRECTCONTAINER)) {
              content = getContainerContent(resourceUri, classOf[LdpDirectContainer], m, preferences)
            } else if (m.getManager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER)) {
              content = getContainerContent(resourceUri, classOf[LdpBasicContainer], m, preferences)
            } else if (m.getManager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_RDFSOURCE)) {
              content = getContainerContent(resourceUri, classOf[LdpRdfSource], m, preferences)
            } else if (m.getManager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_NONRDFSOURCE)) {
              content = getNoneRdfContent(m, resourceUri)
            }
            // FIXME: add a sameAs relation between request uri and $path, if not equal
            if (resourceUri != requestUri && content != null && !content.isEmpty) {
              content += new Statement(resourceUri, OWL.PROPERTY_SAMEAS, requestUri)
            }
            if (content == null || content.isEmpty) Empty
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
      case c@Full(content) => c
      case f@Failure(msg, _, _) => f ~> 500 // something happened, return a 500 status (lift defaults to 404 here)
      case _ => Empty ~> 404
    }
  }

  // PUT, Patch
  protected def updateContent(refs: List[String], req: Req, uri: URI, config: BasicContainerHandler, partial: Boolean): Box[Convertible] = {
    val response: Box[Convertible] = refs match {
      case Nil =>
        unrecognizedResource(req.uri)
      case "index" :: Nil =>
        updateRoot(req,uri,config,partial)
      case path @ _ =>
        val resourceUri = URIs.createURI(req.request.url.replace(req.hostAndPath + "/", uri.trimSegments(2).toString))
        val handler = getHandler(path, config)
        if(!handler.isModifyable){
          val typelinks = computeLinkHeader(handler.getAssignedTo::Nil)
          Full(FailedResponse(OperationResponse.PRECONDITION_FAILED, "resource configured not modifiable", typelinks))
        }
        computeResourceType(resourceUri, req) match {
          case Full((typ, typeLinks, m)) => partial match {
            case false =>
              update(req, typeLinks, resourceUri, typ, m, handler) match {
                case Empty => Full(FailedResponse(OperationResponse.IF_MATCH_MISSING, "missing If-Match header", typeLinks))
                case Full(opRes: OperationResponse) if !opRes.hasError => Full(new UpdateResponse(resourceUri.toString(), typ))
                case Full(opRes: OperationResponse) if opRes.hasError => println("operation resp err: " + opRes.msg()); Full(FailedResponse(opRes.code(), opRes.msg(), typeLinks))
              }
            case true => patchUpdate(req, typeLinks, resourceUri, typ, m, handler)
          }
          //allow to create new Resource if not exist and uri is not reused
          case Empty => req.body.flatMap { b =>
            val bdy = new String(b)
            if (!(bdy.contains("@base") && bdy.contains(resourceUri.toString))) {
              createNewFromPut = true
              createContent(refs, req, resourceUri, config, true)
            }
            else
              Full(FailedResponse(OperationResponse.IF_MATCH_MISSING, "missing If-Match header", constraintHeader))
          }
        }
    }
    response match {
      case c@Full(content) => c
      case f@Failure(msg, _, _) => f ~> 500
      case _ => Empty
    }
  }

  // DELETE
  protected def deleteContent(refs: List[String], req: Req, uri: URI, config: BasicContainerHandler): Box[Convertible] = {

    val response: Box[Convertible] = refs match {
      case Nil => Empty
      case "index" :: Nil =>
        // deleting root container means deleting it's contents only
        findModel(uri).flatMap { m =>
          if (m.getManager.hasMatch(uri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER)) {
            val c = m.getManager.find(uri, classOf[LdpBasicContainer])
            if (config.isDeletable()) {
              c.contains(new java.util.HashSet)
              Full(new UpdateResponse("", LDP.TYPE_BASICCONTAINER))
            } else
              Full(FailedResponse(422, "container configured not deletable", ("Link", constrainedLink) :: Nil))
          } else {
            Full(FailedResponse(422, "root container should be of type Basic, but found another type", ("Link", constrainedLink) :: Nil))
          }

        }
      case path@_ => try {

        val requestedUri = URIs.createURI(req.request.url.replace(req.hostAndPath + "/", uri.trimSegments(2).toString))
        findModel(requestedUri).flatMap(m => {
          val manager = m.getManager
          val res = manager.findRestricted(requestedUri, classOf[LdpRdfSource])
          val handler = getHandler(path, config)
          // remove resource from it's container if exists
          val container = res.getContainer
          val resourceUri =
            if (requestedUri.toString.endsWith("/")) requestedUri.trimSegments(1) else requestedUri
          if (handler.isDeletable()) {
            // remove none RDF resources from store
            if (manager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_NONRDFSOURCE)) {
              val noneRdf = m.getManager.findRestricted(resourceUri, classOf[LdpNoneRdfSource])
              val key = noneRdf.identifier().localPart()
              Globals.fileStore.make.map { fs => fs.delete(key) }
            }
            manager.removeRecursive(requestedUri, true)
            if (res.getURI == requestedUri) new ModelsRest().deleteModel(null, requestedUri)
            val parent = parentUri(requestedUri)
            if (!parent.isEmpty)
              findModel(parent.get).foreach(m => m.getManager.removeRecursive(res.getURI, true))
            findModel(container.getURI).map(m => m.getManager.removeRecursive(resourceUri, true));
            Full(new UpdateResponse("", LDP.TYPE_RESOURCE))
          } else
            Full(FailedResponse(422, "container configured not deletable", ("Link", constrainedLink) :: Nil))
        })
      } catch {
        case e: Exception => Failure(e.getMessage, Some(e), Empty)
      }
    }

    response match {
      case c@Full(content) => c
      case f@Failure(msg, _, _) => f ~> 400
      case _ => Empty
    }
  }

  // POST
  protected def createContent(refs: List[String], req: Req, uri: URI, config: BasicContainerHandler, fromPut: Boolean): Box[Convertible] = {
    val defaultName = fromPut match {
      case false => DEFAULT_NAME
      case true => uri.localPart()
    }
    reqNr+=1
    def requestedSlug = (req.header(SLUG).openOr(defaultName).replaceAll("[^A-Za-z0-9-_.]", "-"), reqNr)
    // the container that the new Resource will be added to it
    def requestedUri = fromPut match {
      case false => URIs.createURI (req.request.url.replace (req.hostAndPath + "/", uri.trimSegments (2).toString) )
      case true => uri.trimSegments(1).appendSegment("")
    }
    def createNoneRdfResource(binaryBody: Array[Byte],resourceUri:URI,model:IModel, conf: RdfResourceHandler): Boolean ={
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
        println("stored resource: "+ fileName + " as binary content type= " + mimeType + " with key=" + fsKey)
        val resourceModel = conf.isSeparateModel match {
          case false => model
          case true =>
            val m = model.getModelSet.createModel(resourceUri)
            m.setLoaded(true)
            m
        }
        val resourceManager = resourceModel.getManager
        //add server-managed properties
        //FIXME use mapping
        resourceManager.add(List(
          new Statement(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_NONRDFSOURCE),
          new Statement(resourceUri, DCTERMS.DCTERMS_PROPERTY_CREATED, new Literal(Instant.now.toString, XMLSCHEMA.TYPE_DATETIME)),
          new Statement(resourceUri, DCTERMS.DCTERMS_PROPERTY_IDENTIFIER, URIs.createURI(s"""blobs:$fsKey""")),
          new Statement(resourceUri, DCTERMS.DCTERMS_PROPERTY_TITLE, fileName),
          new Statement(resourceUri, RDFS.PROPERTY_LABEL, fileName),
          new Statement(resourceUri, DCTERMS.DCTERMS_PROPERTY_FORMAT, mimeType)
        ).asJava)
        true
      }.openOr(false)
    }

    // LDP servers that allow member creation via POST SHOULD NOT re-use URIs. also if the resource was deleted
    val resourceName = fromPut match {
      case false => requestedSlug._1 + "-" + requestedSlug._2
      case true => requestedSlug._1
    }


    def createRdfResource[C <: LdpContainer, RH <: RdfResourceHandler, CH <: ContainerHandler](container :C, m:IModel, resConf:RH, containerConf:CH): Box[Convertible] ={
      val resourceUri = container.getURI.appendLocalPart(resourceName)
      getBodyEntity(req, resourceUri.toString()) match {
        case Left(rdf) =>
          val body = new ReqBodyHelper(rdf, resourceUri)
          val resourceType = ReqBodyHelper.resourceType(req.header(LINK).openOr(null))
          val result = container.createResource (m, resourceType, resConf, containerConf,body)
          if(!result.hasError) {
            Full(new UpdateResponse(resourceUri.toString(), resourceType))
          } else {
            Full(FailedResponse(result.code(), result.msg(), constraintHeader))
          }
        case Right(Full(noneRdfContent)) if(noneRdfContent.length > 0) =>
          if (createNoneRdfResource(noneRdfContent,resourceUri,m,resConf)) {
            Full(new UpdateResponse(resourceUri.toString(), LDP.TYPE_NONRDFSOURCE))
          } else {
            Full(FailedResponse(500, "failed to create resource", constraintHeader))
          }
      }
    }

    val response: Box[Convertible] = refs match {
      //root container can only be  basic
      case Nil => Empty
      case "index" :: Nil => {
        //val constraintHeader =("Link", constrainedLink) :: Nil
        findModel(uri).flatMap(m =>
          if (config.isCreatable() && m.getManager.hasMatch(uri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER)) {
            val handler = config.getContainsHandler
            val container = m.getManager.findRestricted(uri, classOf[LdpBasicContainer])
            createRdfResource(container, m, handler, config)
          } else Full(FailedResponse(412, "root container should be of type basic and configured to be creatable", constraintHeader)))
      }

      case path @ _ => {
        findModel(requestedUri).flatMap(m => {
          if (m.getManager.hasMatch(requestedUri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER)) {
            val bc = m.getManager.findRestricted(requestedUri, classOf[LdpBasicContainer])
            val conf = getHandler(path, config) match {
              case ch: ContainerHandler => ch
              case _ =>  new BasicContainerHandler("")
            }
            val handler = conf.getContainsHandler
            createRdfResource(bc,m,handler, conf)
          } else if (m.getManager.hasMatch(requestedUri, RDF.PROPERTY_TYPE, LDP.TYPE_DIRECTCONTAINER)) {
            //container handler
            val conf = getHandler(path, config) match {
              case dch: DirectContainerHandler => dch
              case _ => println("WARNING: using default DC handler because no container handler found for req=" + requestedUri); new DirectContainerHandler
            }
            //resource handler
            val handler = conf.getContainsHandler
            val c = m.getManager.findRestricted(requestedUri, classOf[LdpDirectContainer])
            createRdfResource(c,m,handler, conf)
          } // TODO add support for indirect containers
          //LDPR and LDPNR don't accept POST (only containers do )
          else {
            Full(new FailedResponse(412, "none container resource shouldn't accept POST request", constraintHeader))
          }
        })
      }
    }
    response match {
      case c @ Full(content) => c
      case f @ Failure(msg, _, _) => f
      case _ => Empty
    }
  }

  def getBodyEntity(req: Req, base: String): Either[Model, Box[Array[Byte]]] = {
    val rdfFormat = Rio.getParserFormatForMIMEType(
      req.request.contentType.openOr(MIME_TURTLE._1 + "/" + MIME_TURTLE._2))
    if (rdfFormat.isPresent)
      Left(Rio.parse(req.request.inputStream, base, rdfFormat.get))
    else
      Right(req.body)
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
    var candidateUri = uri.trimFragment().appendSegment("")
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

  def headerMatch(reqTag: String, requestUri: URI, typ: URI, m: IModel): Box[Boolean] = {
    if (null == reqTag || reqTag.isEmpty) Empty
    else {
      val content: ContainerContent = typ match {
        case LDP.TYPE_BASICCONTAINER => getContainerContent(requestUri, classOf[LdpBasicContainer], m, Empty)
        case LDP.TYPE_DIRECTCONTAINER => getContainerContent(requestUri, classOf[LdpDirectContainer], m, Empty)
        case LDP.TYPE_NONRDFSOURCE => getNoneRdfContent(m, requestUri)
        case _ => getContainerContent(requestUri, classOf[LdpRdfSource], m, Empty)
      }
      //FIXME create new Resource (LDP Server MAY allow the creation of new resources using HTTP PUT
      content.generate(MIME_TURTLE) match {
        case Full((size, _, _, _)) =>
          val sEtag = generateETag(size)
          val sEtags = sEtag.split("-")
          val cEtags = reqTag.split("-")
          if (cEtags.length != 2 || sEtags.length != 2) Full(false)
          else if (cEtags(0) == sEtags(0) || cEtags(1) == sEtags(1)) Full(true)
          else content.fileKey match {
            case Some(key) => Full(true)
            case _ => Full(false)
            //                  Globals.fileStore.make.map{fs =>
            //                  val s = fs.openStream(key).read()
            //                  if(s==cEtags(1)) true
            //                  else false
            //                }
          }
        case _ => Full(false)
      }
    }
  }

  def computeLinkHeader(links: List[URI]): List[(String, String)] = {
    val typeLink = linkHeader(links)
    ("Link", typeLink.head._2 + " ," + constrainedLink) :: Nil
  }

  def getResourceObject(manager: IEntityManager, resourceUri: URI, resourceType: URI) = {
    resourceType match {
      case LDP.TYPE_BASICCONTAINER => manager.findRestricted(resourceUri, classOf[LdpBasicContainer])
      case LDP.TYPE_DIRECTCONTAINER => manager.findRestricted(resourceUri, classOf[LdpDirectContainer])
      case LDP.TYPE_RDFSOURCE => manager.findRestricted(resourceUri, classOf[LdpRdfSource])
    }
  }

  def update(req:Req, typelinks: List[(String,String)], resourceUri: URI, resourceType:URI, m:IModel, config: Handler): Box[OperationResponse] ={
    headerMatch(req.header("If-Match").openOr(""),resourceUri, resourceType, m) match {
      case Full(true) =>
        getBodyEntity(req, resourceUri.toString()) match {
          case Left(rdfBody) =>
            val body = new ReqBodyHelper(rdfBody, resourceUri)
            val manager = m.getManager
            val res = getResourceObject(manager, resourceUri, resourceType)
            Full(res.update(body, config))
          case Right(Full(bin)) =>
            val res = m.getManager.findRestricted(resourceUri, classOf[LdpNoneRdfSource])
            if(res.format() != req.contentType.openOr("application/octet-stream")) {
              Full(new OperationResponse(OperationResponse.PRECONDITION_FAILED, "RDF Resource can not be replaced with binary object"))
            }
            else {
              val fileName = res.fileName()
              Globals.fileStore.make.map { fs => {
                val id = res.identifier().localPart()
                fs.delete(id)
                val fk = fs.store(bin)
                res.fileName(fileName)
                res.identifier(URIs.createURI(s"""blobs:$fk"""))
                res.format(req.contentType.openOr("application/octet-stream"))
                println(s" content of ${res.fileName()} replaced with file key: ${fk}, content type: ${res.format()} ")
                new OperationResponse()
              }}
            }
        }
      case Full(false) => Full(new OperationResponse(OperationResponse.PRECONDITION_FAILED, "IF-MATCH Avoiding mid-air collisions"))
      case Empty =>Empty
      case Failure(msg, _, _) => Full(new OperationResponse(OperationResponse.IF_MATCH_MISSING, msg))
    }
  }

  def patchUpdate(req: Req, typelinks: List[(String, String)], resourceUri: URI, resourceType: URI, m: IModel, config: Handler): Box[Convertible] = {
    println("processing patchUpdate..")
    headerMatch(req.header("If-Match").openOr(""), resourceUri, resourceType, m) match {
      case Full(true) =>
        val in = req.request.inputStream
        val ldpach = ReqBodyHelper.parseLdPatch(in)
        val res = m.getManager.findRestricted(resourceUri, classOf[LdpRdfSource])
        val result = res.updatePartially(ldpach)
        if (!result.hasError) {
          println(result.msg())
          Full(new UpdateResponse(resourceUri.toString(), resourceType))
        } else Full(FailedResponse(result.code(), result.msg(), typelinks))
      case Full(false) => Full(FailedResponse(412, "IF-MATCH Avoiding mid-air collisions ", typelinks))
      case Empty => Full(FailedResponse(428, "", typelinks))
      case Failure(msg, _, _) => Full(FailedResponse(428, msg, typelinks))
    }
  }

  /**
   * Helper class to output the statements in the requested MIME type.
   */
  class ContainerContent(statements: List[IStatement], baseURI: String) extends Convertible {

    val stmts = ListBuffer[IStatement]() ++= statements.toList
    val relTypes = ListBuffer[IReference]() += LDP.TYPE_RESOURCE
    var preference: String = ""

    override def toTurtle(): Box[(Int, OutputStream, String, List[(String, String)])] =
      if (relTypes.contains(LDP.TYPE_NONRDFSOURCE)) generateNoneRdf(MIME_TURTLE)
      else generate(MIME_TURTLE)

    override def toJsonLd(): Box[(Int, OutputStream, String, List[(String, String)])] =
      if (relTypes.contains(LDP.TYPE_NONRDFSOURCE)) generateNoneRdf(MIME_JSONLD)
      else generate(MIME_JSONLD)

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
      if (relType != LDP.TYPE_NONRDFSOURCE) relTypes += LDP.TYPE_RDFSOURCE
      relTypes += relType
    }

    def applyPreference(pref: String) {
      preference = pref
    }

    def isEmpty = 0 == stmts.size

    def fileKey: Option[String] = stmts.find(stmt => stmt.getPredicate == URIs.createURI("http://purl.org/dc/terms/identifier")) match {
      case Some(stmt) => Some(stmt.getObject.asInstanceOf[IReference].getURI.localPart())
      case None => None
    }

    def generateNoneRdf(rdfFormat: (String, String)): Box[(Int, OutputStream, String, List[(String, String)])] = {
      fileKey match {
        case Some(fKey) =>
          val content = Globals.fileStore.make.map { fs =>
            val mime = fs.getProperties(fKey).getProperty("contentType").split("/")
            if (mime.length == 2)
              generate((mime(0), mime(1)))
            else generate(rdfFormat)
          }
          content match {
            case Full(v) => v
            case _ => generate(rdfFormat)
          }
        case None => generate(rdfFormat)
      }
    }

    def generate(mimeTypePair: (String, String)): Box[(Int, OutputStream, String, List[(String, String)])] = {
      val mimeType = mimeTypePair._1 + "/" + mimeTypePair._2
      val output = new ByteArrayOutputStream
      if (mimeTypePair == MIME_TURTLE || mimeTypePair == MIME_JSONLD) {
        try {
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
          if (!preference.isEmpty) ("Preference-Applied", preference) :: headers
          Full((output.size, output, mimeType, headers))
        } catch {
          case e: Exception => e.printStackTrace(); Failure("Unable to generate " + mimeType + ": " + e.getMessage, Full(e), Empty)
        }
      }
      else {
        Globals.fileStore.make.map { fs => {
          val input = fs.openStream(fileKey.get)
          import com.google.common.io.ByteStreams
          ByteStreams.copy(input, output)
        }
        }
        val headers = linkHeader(relTypes.sortBy(_.toString))
        Full((output.size, output, mimeType, headers))
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

  def getContainerContent[T <: LdpRdfSource](resourceUri: URI, clazz: Class[T], m: IModel, prefHeader: Box[String]): ContainerContent = {
    val content = new ContainerContent(Nil, m.getURI.toString)
    val c = m.getManager.find(resourceUri, clazz)
    val pref: (Integer, String) = ReqBodyHelper.preference(prefHeader.openOr(null)).asScala.toList(0)
    content ++= c.getTriples(pref._1).asScala
    content.addRelType(c.getRelType)
    content.applyPreference(pref._2)
    content
  }

  def getNoneRdfContent(m: IModel, resourceUri: URI): ContainerContent = {
    val content = new ContainerContent(Nil, m.getURI.toString)
    content ++= m.getManager.`match`(resourceUri, null, null).toSet
    content.addRelType(LDP.TYPE_NONRDFSOURCE)
    content
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
              case o@_ => o
            }
        }
        val (status: Int, size: Int, text: String, headers: List[(String, String)]) = output match {

          case Full((length, stream, contentType, types)) => {
            r.requestType.post_? || (r.requestType.put_? && createNewFromPut) match {
              case true => (201, length, stream.toString, ("Accept-Post", "*/*") :: ("Accept-Patch" -> "text/ldpatch") :: types)
              case false => (200, length, stream.toString, ("Content-Type", contentType) :: ("Accept-Post", "*/*") ::("Accept-Patch" -> "text/ldpatch") :: ("ETag", generateETag(length)) :: types)
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
          case true => new HeadResponse(size, ("Allow", "OPTIONS, HEAD, GET, POST, PUT, DELETE, PATCH") :: headers, Nil, status)
          case false => PlainTextResponse(text, ("Allow", "OPTIONS, HEAD, GET, POST, PUT, DELETE, PATCH") :: headers, status)
        }
      }
      // ATTN: these next two cases don't actually end up here, lift handles them on its own
      case f@Failure(msg, _, _) => PlainTextResponse("Unable to complete request: " + msg, ("Content-Type", "text/plain") :: Nil, 500)
      case Empty => NotFoundResponse("")
    }
  }

  /**
   * Serve a request returning either Turtle or JSON-LD.
   *
   * @see RestHelper#serveJx[T](pf: PartialFunction[Req, BoxOrRaw[T]]): Unit
   */
  protected def serveTj[T](pf: PartialFunction[Req, BoxOrRaw[T]]): Unit = serveType(tjSel)(pf)(cvt)

  /**
   * Generate the LiftResponse appropriate for the output format from the query result T.
   */
  implicit def cvt[T]: PartialFunction[(TurtleJsonLdSelect, T, Req), LiftResponse] = {
    case (s: TurtleJsonLdSelect, t: Convertible, r: Req) => generateResponse(s, t, r)
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
    extends OutputStreamResponse({
      _.close
    }, size: Long, headers: List[(String, String)], cookies: List[HTTPCookie], code: Int) {
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


