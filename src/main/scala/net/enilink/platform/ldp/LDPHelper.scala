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
import org.eclipse.rdf4j.model.Model

import javax.xml.datatype.XMLGregorianCalendar
import net.enilink.komma.core.IReference
import net.enilink.komma.core.IValue
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
import org.eclipse.rdf4j.rio.RDFFormat
import net.enilink.komma.model.IModelSet
import scala.tools.nsc.interactive.Response
import net.enilink.komma.parser.sparql.tree.BNode
import org.eclipse.rdf4j.model.IRI
import net.enilink.komma.em.concepts.IResource
import org.eclipse.rdf4j.model.impl.SimpleNamespace
import net.enilink.platform.confog._

/**
 * Linked Data Platform (LDP) endpoint support.
 * @see http://www.w3.org/TR/ldp/
 */
class LDPHelper extends RestHelper {
  
  val DCTERMS = URIs.createURI("http://purl.org/dc/terms/")
  val DCTERMS_PROPERTY_CREATED = DCTERMS.appendSegment("created")
  val DCTERMS_PROPERTY_MODIFIED = DCTERMS.appendSegment("modified")
  val DCTERMS_RELATION = URIs.createURI("http://purl.org/dc/terms/relation")
  val SLUG ="Slug"
  val LINK ="Link"
  val PREFER="Prefer"
  val DEFAULT_NAME = "resource"
  //FIXME just now for testing 
  val constrainedLink ="""<https://www.w3.org/TR/ldp/>;rel="http://www.w3.org/ns/ldp#constrainedBy" """
  val MIME_TURTLE = ("text", "turtle")
  val MIME_JSONLD = ("application", "ld+json")
  var reqNr = 0
 
  val valueConverter = new RDF4JValueConverter(SimpleValueFactory.getInstance)
  // turtle/json-ld distinction is made by tjSel and using the Convertible in cvt below
  // FIXME: support LDP without extra prefix, on requests for plain resource URIs with no other match
  def register(path: String, uri: URI, handler:RdfResourceHandler) = {
    serveTj {
    // use backticks to match on path's value
    
    case Options(`path` :: refs, req) => getOptions(refs, req)
    case Head(`path` :: refs, req) => getContent(refs, req, uri)
    case Get(`path` :: refs, req) => println(refs); getContent(refs, req, uri)
    case Post(`path` :: refs, req) => { println(refs);reqNr = reqNr + 1; createContent(refs, req, uri, reqNr) }
    case Put(`path` :: refs, req) => updateContent(refs, req, uri)
    case Delete(`path` :: refs, req) => deleteContent(refs, req, uri)

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
  
  class UpdateResponse(location: String, typ:URI) extends Convertible {
    def noContent: Box[(Int, OutputStream, String, List[(String, String)])] = {
      val relTypes = ListBuffer[IReference]() ++= List(typ)
      val header = if (!location.isEmpty())
                      ("Location" -> location):: linkHeader(relTypes.sortBy(_.toString))
                   else linkHeader(relTypes.sortBy(_.toString))
      Full(0, new ByteArrayOutputStream,"",header)
    }
    override def toTurtle(): Box[(Int, OutputStream, String, List[(String, String)])] = noContent
    override def toJsonLd(): Box[(Int, OutputStream, String, List[(String, String)])] = noContent
  }

  case class FailedResponse(code:Int, msg:String, headers:  List[(String, String)]) extends Convertible{
    override def toTurtle() = Failure(msg, Empty, Empty)
    override def toJsonLd() =Failure(msg, Empty, Empty)
  }
  
  protected def getContent(refs: List[String], req: Req, uri: URI): Box[Convertible] = {
    val requestUri = URIs.createURI(req.request.url)
    val preferences:(Int,String) = req.header(PREFER) match{
              case Empty => (PreferenceHelper.defaultPreferences,"")
              case Full(s) => 
                s.split(";").map(_.trim).toList match{
                  case rep::action::Nil =>
                    //content.applyPreference(rep)
                    val uriToPrefs = Map(LDP.PREFERENCE_MINIMALCONTAINER.toString() -> PreferenceHelper.MINIMAL_CONTAINER, 
                            LDP.PREFERENCE_CONTAINMENT.toString() ->PreferenceHelper.INCLUDE_CONTAINMENT,
                            LDP.PREFERENCE_MEMBERSHIP.toString() ->PreferenceHelper.INCLUDE_MEMBERSHIP)
                            .withDefaultValue(PreferenceHelper.defaultPreferences())
                     
                    action.split("=").map(_.trim).toList match{
                      case "include"::prefs::Nil =>
                        var  acc =0
                        prefs.split(" ").map(_.trim).map{p => 
                          println("p: "+p)
                          println("p stripped: "+ p.replace("\"",""))
                          println(" mapped to: "+  uriToPrefs(p.replace("\"","")))
                          acc = acc | uriToPrefs(p.replace("\"",""))
                          }
                        if (acc > 0) (acc, rep)
                        else (PreferenceHelper.defaultPreferences(), rep)
                      case "omit"::prefs::Nil =>
                        var acc =PreferenceHelper.defaultPreferences()
                        prefs.split(" ").map(_.trim).map(p => acc = acc & uriToPrefs(p.replace("\"","")))
                        (acc,rep)
                      case _ =>( PreferenceHelper.defaultPreferences(), rep)
                    }
                  case _ => (PreferenceHelper.defaultPreferences(), "")
                }
              case _ => (PreferenceHelper.defaultPreferences(),"")
            }
            
println("preferences: "+preferences)
    val content: Box[ContainerContent] = refs match {
      case Nil =>  Empty
      // FIXME: /DOM/ will return a container with all registered memories
      case "index" :: Nil =>
        findModel(uri).flatMap { m =>
          if (m.getManager.hasMatch(uri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER)) {
            val root = m.getManager.findRestricted(uri, classOf[LdpBasicContainer])
            val content = new ContainerContent(Nil, m.getURI.toString)
            content.addRelType(root.getRelType)
            content ++= root.getTriples(preferences._1)
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

      // FIXME: /DOM/$path
      case path @ _ =>
        try {
         val resourceUri = URIs.createURI(req.request.url.replace(req.hostAndPath + "/", uri.trimSegments(2).toString))
          // FIXME: assumption: resource is contained in model using the memory as URI
         println( "path head options: "+ path.headOption)
         println("uri :" +uri)
         println("resourceUri: "+resourceUri)
         println("request url*: "+ req.request.url) 
         path.headOption.flatMap { id =>
            findModel(uri.appendLocalPart(id).appendSegment("")).flatMap { m =>
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
  protected def updateContent(refs: List[String], req: Req, uri: URI) = Box[Convertible] {
    def headerMatch(requestUri:URI): Box[Boolean] ={
      if (req.header("If-Match").isEmpty) Empty
      else{
        val containerContent = getContent(refs, req, requestUri)
        println("container content: " + containerContent)
          containerContent match {
           case Full(content) => 
             val c = content.asInstanceOf[ContainerContent]
             println("generated Etag: "+c.generate("text" -> "turtle"))
              c.generate("text" -> "turtle") match {
               case Full((size, stream, mime, headers)) => 
                 val cEtag = req.header("If-Match").get
                 val sEtag = generateETag(stream)
                 println("cEtag: "+cEtag)
                 println("cEtag: "+sEtag)
                  if(sEtag == cEtag) Full(true)
                  else Full(false)
               case _ => Full(false)
             }
            //FIXME create new Resource (LDP Server may allow the creation of new resources using HTTP PUT
           case _ => Full(false)  
        }
      }
    }
    val response: Box[Convertible] = refs match {
      //FIXME make graceful and configurable constraints
      case Nil =>
        val typeLink = linkHeader(LDP.TYPE_RDFSOURCE::Nil)
        val tail = typeLink.head._2 + " ," + constrainedLink
        val msg = s"not recognized as Resource: ${req.uri}, try ${req.uri}/ "
        Full(new FailedResponse(404, msg, ("Link", tail)::Nil))
      case "index" :: Nil => 
         val typelinks = linkHeader(LDP.TYPE_RESOURCE::LDP.TYPE_BASICCONTAINER::Nil)
          val tail = typelinks.head._2 + " ," + constrainedLink
          headerMatch(uri) match {
           case Full(true) =>
            findModel(uri).flatMap(m => {
              val manager = m.getManager
              if( manager.hasMatch(uri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER)) {
                ReqBodyHelper.getBodyEntity(req, uri.toString()) match{
                   case Left(rdfBody) =>
                     if (ReqBodyHelper.isBasicContainer(rdfBody, uri)) {
                     //replace
                       manager.removeRecursive(uri, true)
                       rdfBody.map(stmt => {
                         val subj = valueConverter.fromRdf4j(stmt.getSubject())
                         val pred = valueConverter.fromRdf4j(stmt.getPredicate())
                         val obj =  valueConverter.fromRdf4j(stmt.getObject())
                         if(subj ==uri && !ReqBodyHelper.isServerProperty(pred))
                           manager.add( new Statement(subj, pred, obj))  
                        })
                        manager.add(new Statement(uri, DCTERMS_PROPERTY_MODIFIED, 
                                                        new Literal(Instant.now.toString, XMLSCHEMA.TYPE_DATETIME)))
                  
                        Full(new UpdateResponse(uri.toString(),  LDP.TYPE_BASICCONTAINER))
                       } else {
                         Full(new FailedResponse(409, "Basic Container should be replaced with basic container",   ("Link", tail)::Nil))
                       }
                     case Right(bin) => 
                       Full(new FailedResponse(412, "Basic Container can not be replaced with binary object", ("Link", tail)::Nil))
              
                 }
              
             } else
              Full(new FailedResponse(412, "root container schould be Basic Container but found another type",  ("Link", tail)::Nil))
                
         })
         case Full(false) => Full(new FailedResponse(412, "IF-MATCH Avoiding mid-air collisions ",  ("Link", tail)::Nil))
         case Empty => Full(new FailedResponse(428, "",  ("Link", tail)::Nil))
         }
       
      case path @ _ =>
        val typelinks = linkHeader(LDP.TYPE_RESOURCE::LDP.TYPE_BASICCONTAINER::Nil)
        val tail = typelinks.head._2 + " ," + constrainedLink
        val resourceUri = URIs.createURI(req.request.url.replace(req.hostAndPath + "/", uri.trimSegments(2).toString))
        println(resourceUri)
        headerMatch(resourceUri) match{
          case Full(true) =>
           findModel(resourceUri).flatMap( m => {
            val manager = m.getManager
            if( manager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER)){
              
              ReqBodyHelper.getBodyEntity(req, resourceUri.toString()) match{
                   case Left(rdfBody) =>
                     if (ReqBodyHelper.isBasicContainer(rdfBody, resourceUri)) {
                     //replace
                       manager.removeRecursive(resourceUri, true)
                       rdfBody.map(stmt => {
                         val subj = valueConverter.fromRdf4j(stmt.getSubject())
                         val pred = valueConverter.fromRdf4j(stmt.getPredicate())
                         val obj =  valueConverter.fromRdf4j(stmt.getObject())
                         if(subj ==resourceUri && !ReqBodyHelper.isServerProperty(pred))
                           manager.add( new Statement(subj, pred, obj))  
                        })
                        manager.add(new Statement(resourceUri, DCTERMS_PROPERTY_MODIFIED, 
                                                        new Literal(Instant.now.toString, XMLSCHEMA.TYPE_DATETIME)))
                  
                        Full(new UpdateResponse(resourceUri.toString(),  LDP.TYPE_BASICCONTAINER))
                       } else {
                         Full(new FailedResponse(409, "Basic Container should be replaced with basic container", ("Link", tail)::Nil))
                       }
                     case Right(bin) => 
                       Full(new FailedResponse(412, "Changing container type not allowed. Basic Container can not be replaced with binary object", ("Link", tail)::Nil))
              
                 }
            } else if( manager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_DIRECTCONTAINER)){
              val typelinks = linkHeader(LDP.TYPE_RESOURCE::LDP.TYPE_DIRECTCONTAINER::Nil)
              ReqBodyHelper.getBodyEntity(req, resourceUri.toString()) match{
                   case Left(rdfBody) =>
                     if (ReqBodyHelper.isDirectContainer(rdfBody, resourceUri)) {
                       //repair membership relations
                       val c = manager.findRestricted(resourceUri, classOf[LdpDirectContainer])
                       findModel(c.membershipResource.getURI) match{
                         case Full(relModel) =>
                           val relManager = relModel.getManager
                           val it= c.contains.iterator()
                           while(it.hasNext())  relManager.remove(c.membershipResource,c.hasMemberRelation,it.next)
                        }
                        
                         // replace
                         manager.removeRecursive(resourceUri, true)
                         var contains:List[URI]=Nil
                         var relSrc:URI =null
                         var hasRel:URI = null
                         rdfBody.map(stmt => {
                         val subj = valueConverter.fromRdf4j(stmt.getSubject())
                         val pred = valueConverter.fromRdf4j(stmt.getPredicate())
                         val obj =  valueConverter.fromRdf4j(stmt.getObject())
                         if(subj ==resourceUri && !ReqBodyHelper.isServerProperty(pred))
                           manager.add( new Statement(subj, pred, obj)) 
                         if (pred == LDP.PROPERTY_CONTAINS && subj ==  resourceUri) obj::contains
                         if( pred == LDP.PROPERTY_HASMEMBERRELATION && subj == resourceUri) hasRel = obj.asInstanceOf[IReference].getURI
                         if(pred == LDP.PROPERTY_MEMBERSHIPRESOURCE && subj ==resourceUri ) relSrc= obj.asInstanceOf[IReference].getURI
                        })
                        manager.add(new Statement(resourceUri, DCTERMS_PROPERTY_MODIFIED, 
                                                        new Literal(Instant.now.toString, XMLSCHEMA.TYPE_DATETIME)))
                       findModel(relSrc) match{
                           case Full(relM) => contains.map(elem => relM.getManager.add(new Statement(relSrc,hasRel,elem)))
                         }
                       Full(new UpdateResponse(resourceUri.toString(),  LDP.TYPE_DIRECTCONTAINER))
                     } else 
                         Full(new FailedResponse(409, "Changing container type not allowed. Direct Container should be replaced with Direct container",("Link", tail)::Nil))
                   case Right(bin) =>
                     Full(new FailedResponse(412, "Direct Container should be replaced with Direct container", ("Link", tail)::Nil))
              }
            } else if( manager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_RDFSOURCE)) {
              val typelinks = linkHeader(LDP.TYPE_RDFSOURCE::Nil)
              ReqBodyHelper.getBodyEntity(req, resourceUri.toString()) match{
                   case Left(rdfBody) =>
                       manager.removeRecursive(resourceUri, true)
                       rdfBody.map(stmt => {
                         val subj = valueConverter.fromRdf4j(stmt.getSubject())
                         val pred = valueConverter.fromRdf4j(stmt.getPredicate())
                         val obj =  valueConverter.fromRdf4j(stmt.getObject())
                         if(subj ==resourceUri && !ReqBodyHelper.isServerProperty(pred))
                           manager.add( new Statement(subj, pred, obj))  
                        })
                        manager.add(new Statement(resourceUri, DCTERMS_PROPERTY_MODIFIED, 
                                                        new Literal(Instant.now.toString, XMLSCHEMA.TYPE_DATETIME)))
                  
                        Full(new UpdateResponse(resourceUri.toString(),  LDP.TYPE_RDFSOURCE))
                     
                   case Right => Full(new FailedResponse(412, "conflict ..", ("Link", tail)::Nil))  
              }
             // Full(new FailedResponse(428, "RDF Resource should be replaced with resource from same type",  ("Link", tail)::Nil))  
            }else if( manager.hasMatch(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_RESOURCE)){
               Full(new FailedResponse(412, "not supported yet ..",  ("Link", tail)::Nil)) 
            }else  Full(new FailedResponse(412, "not recognized Resource",  ("Link", tail)::Nil))
            })
            case Full(false) => Full(new FailedResponse(412, "IF-Match, Avoiding mid-air collisions", ("Link", tail)::Nil)) 
            case Empty => Full(new FailedResponse(428, "", ("Link", tail)::Nil))  
           }  
        } 
                     
       
    println("put respobse: "+response)
    response match {
      case c @ Full(content) => c
      case f @ Failure(msg, _, _)  => f ~> 500 
       

      case _ => Empty
    }

  }
  protected def deleteContent(refs: List[String], req: Req, uri: URI) = Box[Convertible] {
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
             c.contains(new java.util.HashSet)
//           val stmts= m.getManager.`match`(uri, LDP.PROPERTY_CONTAINS, null)
//           stmts.forEach(stmt => m.getManager.removeRecursive(stmt.getObject, true))
           
            Full(new UpdateResponse("",LDP.TYPE_BASICCONTAINER))
          } else {
             // not supported
            Full(new FailedResponse(422, "root container schoud be of type Basic, but found another type", ("Link",constrainedLink)::Nil)) 
          }
          
      }
      case path @ _ => try {
          val requestedUri = URIs.createURI(req.request.url.replace(req.hostAndPath + "/", uri.trimSegments(2).toString))
          def parentUri: Option[URI] = 
            if (requestedUri.segmentCount > 1 && requestedUri.toString.endsWith("/"))
              Some(requestedUri.trimSegments(2).appendSegment(""))
            else if (requestedUri.segmentCount > 0) 
              Some(requestedUri.trimSegments(1).appendSegment(""))
            else None
          findModel(requestedUri).flatMap ( m => {
               m.getManager.removeRecursive(requestedUri, true)
               if(m.getURI == requestedUri) ModelsRest.deleteModel(null, requestedUri)
               if(!parentUri.isEmpty) 
                 findModel(parentUri.get).foreach(m => m.getManager.removeRecursive(requestedUri, true))
               
                Full(new UpdateResponse("",LDP.TYPE_RESOURCE))
               })
      } catch {
        case e: Exception => Failure(e.getMessage, Some(e), Empty)
      }     
   }

    response match {
      case c @ Full(content) => c
      case f @ Failure(msg, _, _) =>  f ~> 400
      case _ => Empty
    }
  }

  protected def createContent(refs: List[String], req: Req, uri: URI, reqNr: Int) = Box[Convertible] {
    def createResource(modelSet:IModelSet, containerUri: URI, isRoot:Boolean) = {
      val resourceUri =  containerUri.appendLocalPart(resourceName).appendSegment("")
      ReqBodyHelper.getBodyEntity(req,  resourceUri.toString()) match{
           case Left(rdfBody) =>
             println(s"model in req: ${rdfBody}")
              val typ =isRoot match {
                  case true => LDP.TYPE_BASICCONTAINER
                  case false => resourceType
                  }
              println(s"Resource to be created is from type ${typ}")
              val valid = typ match {
                  case LDP.TYPE_BASICCONTAINER =>  ReqBodyHelper.isBasicContainer(rdfBody, resourceUri)
                  case LDP.TYPE_DIRECTCONTAINER  => ReqBodyHelper.isDirectContainer(rdfBody, resourceUri)
                  case LDP.TYPE_RDFSOURCE => true
                //TODO add support to indirect containers
                  case _ => false
                 }
              if(valid){
                   println("content ist valid")
                 modelSet.getUnitOfWork.begin
                 try{
                     val resourceModel = modelSet.createModel(resourceUri)
                     resourceModel.setLoaded(true)
                     val resourceManager = resourceModel.getManager
                     rdfBody.map(stmt =>{
                       val subj = valueConverter.fromRdf4j(stmt.getSubject())
                       val pred = valueConverter.fromRdf4j(stmt.getPredicate())
                       val obj =  valueConverter.fromRdf4j(stmt.getObject())
                       if(!(subj==resourceUri && ReqBodyHelper.isServerProperty(pred)))
                         resourceManager.add( new Statement(subj, pred, obj))  
                      })
                      resourceManager.add(List(
                          new Statement(resourceUri, DCTERMS_PROPERTY_CREATED, new Literal(Instant.now.toString, XMLSCHEMA.TYPE_DATETIME)),
                          new Statement(resourceUri,  RDF.PROPERTY_TYPE, LDP.TYPE_RDFSOURCE)))
                      Full(resourceUri,typ)
                    } catch {
                       case t: Throwable => Failure(t.getMessage, Some(t), Empty)
                    } finally {
                         modelSet.getUnitOfWork.end
                    }
                } else {println("not valid or complete RDF content");Empty}
                 
            // TODO handle LDPNR case   
            case Right(binaryBody) =>  {println("none RDF content, binary content");Empty}
          } 
      }
    
    def resourceName =  (req.header(SLUG).openOr(DEFAULT_NAME) + reqNr.toString()).
        toLowerCase().replaceAll("[^a-z0-9-]", "-")
    def requestedUri = URIs.createURI(req.request.url.replace(req.hostAndPath + "/", uri.trimSegments(2).toString))
    println("requested uri : "+requestedUri)
    //FIXME
    def resourceType =req.header(LINK)  match {
      case Full(l) =>
        val t =  l.split(";").map(_.trim).apply(0)
        URIs.createURI(t.substring(1, t.length -1), true)
      case _ => LDP.TYPE_RDFSOURCE
    }
    
        println("resource type: "+resourceType)
       println("headers; "+req.request.headers(LINK))
    
    
    val response: Box[Convertible] = refs match {
      //root container can only be  basic
      case Nil => Empty
      case "index" :: Nil => {
       findModel(uri).flatMap(m => 
            if (m.getManager.hasMatch(uri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER)) {
               println("create resource in root container..")
               println("uri : "+uri)
              createResource(m.getModelSet,uri,false) match {
                case Full((resultUri, typ)) =>
                  println("resultUri: "+resultUri)
                  println("type: "+typ)
                   m.getManager.add(new Statement(uri, LDP.PROPERTY_CONTAINS, resultUri))
                   Full(new UpdateResponse(resultUri.toString(),typ))
                case Empty => 
                  Full(new FailedResponse(415,"not valid body entety or violation of constraints",("Link",constrainedLink)::Nil))
                case f: Failure => f 
              }
            } else Full(new FailedResponse(412,"root container should be of type basic",("Link",constrainedLink)::Nil))
          )
          
      }

      case path @ _ => {
        
        findModel(requestedUri).flatMap(m => {
          println("model: "+m)
          
          if (m.getManager.hasMatch(requestedUri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER)) 
            createResource(m.getModelSet,requestedUri,false) match {
              case Full((resultUri,typ)) => 
                 m.getManager.add(new Statement(requestedUri, LDP.PROPERTY_CONTAINS, resultUri))
                 Full(new UpdateResponse(resultUri.toString(),typ))
              case Empty =>
                Full(new FailedResponse(415,"not valid body entety or violation of constraints",("Link",constrainedLink)::Nil))
              case f: Failure => f
            }
           
          else if (m.getManager.hasMatch(requestedUri, RDF.PROPERTY_TYPE, LDP.TYPE_DIRECTCONTAINER)) {
            println("creating resource in direct container, not root")
            createResource(m.getModelSet,requestedUri,false) match {
              case Full((resultUri,typ)) => 
                 m.getManager.add(new Statement(requestedUri, LDP.PROPERTY_CONTAINS, resultUri))
                 val c = m.getManager.findRestricted(requestedUri, classOf[LdpDirectContainer] )
                 val relationSourceUri = c.membershipResource().getURI
                 val relationSourceModel = m.getModelSet.getModel(relationSourceUri, false)
                 relationSourceModel.getManager.add(new Statement(relationSourceUri, 
                                                     c.hasMemberRelation().getURI, resultUri))
                 Full(new UpdateResponse(resultUri.toString(),typ))
              case Empty => 
                Full(new FailedResponse(415,"not valid body entety or violation of constraints",("Link",constrainedLink)::Nil)) //bad request
              case f: Failure => f
            }
          }
          // TODO add support for indirect containers
          //LDPR and LDPNR don't accept POST (only containers do )
          else 
            Full(new FailedResponse(412,"none container resource shouldn't accept POST request",("Link",constrainedLink)::Nil)) //bad request
        })
      }  
    }
    response  match {
      case c @ Full(content) => c
      case f @ Failure(msg, _, _) => f
      case _ => Empty
    }
  }
  object ReqBodyHelper {
    val systemProperties = List(DCTERMS_PROPERTY_CREATED,DCTERMS_PROPERTY_MODIFIED)
    def getBodyEntity(req:Req, base: String):Either[Model,Box[Array[Byte]]]={
      val rdfFormat = Rio.getParserFormatForMIMEType(
                req.request.contentType.openOr(MIME_TURTLE._1 + "/" + MIME_TURTLE._2)) 
      if(rdfFormat.isPresent) 
        Left(Rio.parse(req.request.inputStream, base, rdfFormat.get))
      else 
        Right(req.body)
    }
   def isResource(m:Model, resourceUri:URI) =  m.contains(
                    valueConverter.toRdf4j(resourceUri), 
                    valueConverter.toRdf4j(RDF.PROPERTY_TYPE),
                    valueConverter.toRdf4j(LDP.TYPE_RESOURCE))
   def isRdfResource(m:Model, resourceUri:URI):Boolean = m.contains(
                    valueConverter.toRdf4j(resourceUri), 
                    valueConverter.toRdf4j(RDF.PROPERTY_TYPE),
                    valueConverter.toRdf4j(LDP.TYPE_RDFSOURCE))
   def isContainer(m: Model,resourceUri:URI): Boolean = isRdfResource(m, resourceUri) && m.contains(
                    valueConverter.toRdf4j(resourceUri), 
                    valueConverter.toRdf4j(RDF.PROPERTY_TYPE),
                    valueConverter.toRdf4j(LDP.TYPE_CONTAINER)) && isNoContains(m,resourceUri)
   def isBasicContainer(m:Model, resourceUri:URI) = isRdfResource(m, resourceUri) && m.contains(
                    valueConverter.toRdf4j(resourceUri), 
                    valueConverter.toRdf4j(RDF.PROPERTY_TYPE),
                    valueConverter.toRdf4j(LDP.TYPE_BASICCONTAINER)) && isNoContains(m,resourceUri)
   def hasRelationshipResource(m:Model, resourceUri:URI) = m.predicates.contains(
                                        valueConverter.toRdf4j(LDP.PROPERTY_MEMBERSHIPRESOURCE))
   def hasReletionship(m:Model, resourceUri:URI) = {println(s"request model has predicates: ${m.predicates}");  m.predicates.contains(
                                        valueConverter.toRdf4j(LDP.PROPERTY_HASMEMBERRELATION))}
   def isNoContains(m:Model, resourceUri:URI) =  m.filter(valueConverter.toRdf4j(resourceUri), valueConverter.toRdf4j(LDP.PROPERTY_CONTAINS), null).isEmpty()
  
   def isDirectContainer(m:Model, resourceUri:URI) =isRdfResource(m, resourceUri) && m.contains(
                    valueConverter.toRdf4j(resourceUri), 
                    valueConverter.toRdf4j(RDF.PROPERTY_TYPE),
                    valueConverter.toRdf4j(LDP.TYPE_DIRECTCONTAINER)) &&
                    hasReletionship(m, resourceUri) &&
                    hasRelationshipResource(m, resourceUri) && isNoContains(m, resourceUri)
                    
   def isServerProperty(prop: IReference) = systemProperties.contains(prop)
                          
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
    var candidateUri = uri.trimFragment()
    var done = false
    while (!done) {
      result = Globals.contextModelSet.vend.flatMap { ms =>
        // try to get the model from the model set
        println("trying "+ candidateUri)
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
              else  candidateUri = candidateUri.trimSegments(1).appendSegment("")
          }
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
    var preference :String=""

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
    def applyPreference (pref: String){
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
          val headers =linkHeader(relTypes.sortBy(_.toString))
          if(!preference.isEmpty) ("Prefer", preference)::headers
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

  protected def generateETag(stream: OutputStream): String = {
    val date = System.currentTimeMillis
    "W/\"" + java.lang.Long.toString(date - date % 60000) + "\""
  }

  protected def generateResponse[T](s: TurtleJsonLdSelect, t: T, r: Req): LiftResponse = {
    println("t : "+t)
    t match {
      case c: Convertible => {
       println("c: "+c)
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
              case true => (201, length, stream.toString,  ("Accept-Post", "*/*") :: types)
              case false => (200, length, stream.toString, ("Content-Type", contentType) :: ("Accept-Post", "*/*") :: ("ETag", generateETag(stream)) :: types)
            }
          }
          case Failure(msg, _, _) =>
            c match {
              case FailedResponse(code, msg, links) =>println("code: "+code)
                (code, msg.length,msg, links)
              case _ => (500, msg.length, msg, Nil)
          }

         case Empty =>{println("empty inner"); (404, 0, "", Nil)}
        }

        r.requestType.head_? match {
          case true => new HeadResponse(size, ("Allow", "OPTIONS, HEAD, GET, POST, PUT, DELETE") :: headers, Nil, status)
          case false => PlainTextResponse(text, ("Allow", "OPTIONS, HEAD, GET, POST, PUT, DELETE") :: headers, status)
        }
      }
      // ATTN: these next two cases don't actually end up here, lift handles them on its own
      case f @ Failure(msg, _, _) =>  PlainTextResponse("Unable to complete request: " + msg, ("Content-Type", "text/plain") :: Nil, 500) 
      case Empty => {println("empty out");NotFoundResponse()}
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
