package net.enilink.platform.lift

import net.enilink.komma.core.{BlankNode, IUnitOfWork, URIs}
import net.enilink.platform.lift.html.Html5ParserWithRDFaPrefixes
import net.enilink.platform.lift.rest.FileService
import net.enilink.platform.lift.util.{CurrentContext, Globals, NotAllowedModel, RdfContext}
import net.enilink.platform.security.auth.{AccountHelper, EnilinkPrincipal}
import net.liftweb.common.Box.option2Box
import net.liftweb.common.{Box, Empty, Full, Logger}
import net.liftweb.http.ContentSourceRestriction.{Self, UnsafeEval, UnsafeInline}
import net.liftweb.http.LiftRulesMocker.toLiftRules
import net.liftweb.http.auth.HttpBasicAuthentication
import net.liftweb.http.js.jquery.JQueryArtifacts
import net.liftweb.http.{ContentSecurityPolicy, ForbiddenResponse, Html5Properties, LiftRules, LiftSession, NoticeType, OnDiskFileParamHolder, Req, ResourceServer, S, SecurityRules}
import net.liftweb.util.Helpers.intToTimeSpanBuilder
import net.liftweb.util.Vendor.{funcToVendor, valToVendor}
import net.liftweb.util._

import java.security.{AccessController, PrivilegedAction}
import java.util.{Collections, Locale}
import javax.security.auth.Subject
import scala.language.postfixOps
import scala.xml.NodeSeq

/**
 * A class that's instantiated early and run.  It allows the application
 * to modify lift's environment
 */
class LiftModule extends Logger {
  /**
   * Template cache that stores templates per application.
   */
  class InMemoryCache(templatesCount: Int) extends TemplateCache[(Locale, List[String]), NodeSeq] {
    private val cache: LRU[(List[String], (Locale, List[String])), NodeSeq] = new LRU(templatesCount)

    private def withApp(key: T) = (Globals.application.vend.dmap(Nil: List[String])(_.path), key)

    def get(key: T): Box[NodeSeq] = cache.synchronized {
      cache.get(withApp(key))
    }

    def set(key: T, node: NodeSeq): NodeSeq = cache.synchronized {
      cache(withApp(key)) = node
      node
    }

    override def delete(key: T): Unit = {
      cache.synchronized(cache.remove(withApp(key)))
    }
  }

  def boot(): Unit = {
    // enable this to rewrite application paths (WIP)
    // ApplicationPaths.rewriteApplicationPaths

    // enable this to extract inline Javascript for using a more restrictive CSP
    LiftRules.extractInlineJavaScript = false

    // define liberal security rules for now
    LiftRules.securityRules = () => SecurityRules(
      content = Some(
        ContentSecurityPolicy(
          defaultSources = List(Self, UnsafeInline),
          scriptSources = List(Self, UnsafeInline, UnsafeEval)
        )
      )
    )

    // remove X-Frame-Options for now to allow embedding via iframe
    val supplementalHeaders = LiftRules.supplementalHeaders.vend
    LiftRules.supplementalHeaders.default.set(() => {
      supplementalHeaders.filter { case (key, _) => key != "X-Frame-Options" }
    })

    // set context user from EnilinkPrincipal contained in the HTTP session after successful login
    Globals.contextUser.default.set(() => {
      Subject.getSubject(AccessController.getContext) match {
        case s: Subject =>
          val userPrincipals = s.getPrincipals(classOf[EnilinkPrincipal])
          if (!userPrincipals.isEmpty) userPrincipals.iterator.next.getId else Globals.UNKNOWN_USER
        case _ => Globals.UNKNOWN_USER
      }
    })

    //    LiftRules.resourceBundleFactories prepend {
    //      case (basename, locale) => ResourceBundle.getBundle(basename, locale)
    //    }

    // simple fix to overwrite zero or negative inactive intervals
    LiftRules.sessionCreator = {
      case (httpSession, contextPath) =>
        if (httpSession.maxInactiveInterval <= 0) {
          // use LiftRules.sessionInactivityTimeout or default value of 30 minutes
          // maxInactiveInterval is in seconds
          httpSession.setMaxInactiveInterval(LiftRules.sessionInactivityTimeout.vend.map(_ / 1000) openOr 30 * 60)
        }
        new LiftSession(contextPath, httpSession.sessionId, Full(httpSession))
    }

    // Use jQuery 1.8.2
    LiftRules.jsArtifacts = new JQueryArtifacts {
      override def pathRewriter: PartialFunction[List[String], List[String]] = {
        case "jquery.js" :: Nil if Props.devMode => List("jquery", "jquery-1.10.2.js")
        case "jquery.js" :: Nil => List("jquery", "jquery-1.10.2.min.js")
      }
    }

    // Show the spinny image when an Ajax call starts
    LiftRules.ajaxStart =
      Full(() => LiftRules.jsArtifacts.show("ajax-loader").cmd)

    // Make the spinny image go away when it ends
    LiftRules.ajaxEnd =
      Full(() => LiftRules.jsArtifacts.hide("ajax-loader").cmd)

    // fade out information notices
    LiftRules.noticesAutoFadeOut.default.set((notices: NoticeType.Value) => {
      notices match {
        case NoticeType.Notice => Full((3 seconds, 2 seconds))
        case _ => Empty
      }
    })

    // What is the function to test if a user is logged in?
    LiftRules.loggedInTest = Full(() => Globals.contextUser.vend != Globals.UNKNOWN_USER)

    // add @prefix support to HTML parser
    object Html5ParserForRDFa extends Html5ParserWithRDFaPrefixes
    // Use HTML5 for parsing and rendering
    LiftRules.htmlProperties.default.set((r: Req) =>
      Html5Properties(r.userAgent).setHtmlParser(Html5ParserForRDFa.parse))

    // Force the request to be UTF-8
    LiftRules.early.append(_.setCharacterEncoding("UTF-8"))

    // save uploaded files to disk
    LiftRules.handleMimeFile = OnDiskFileParamHolder.apply
    // register REST service for file management
    LiftRules.dispatch.append(FileService)

    // modify cache control for the FileService
    val defaultHeaders = LiftRules.defaultHeaders
    LiftRules.defaultHeaders = {
      case (_, Req("files" :: _, _, _)) =>
        List("Cache-Control" -> "private", "Pragma" -> "")
      case other => defaultHeaders(other)
    }

    // register REST service for Linked Data Platform support
    // LiftRules.dispatch.append(LDPService)

    // dispatch function for checking access to context model
    LiftRules.dispatch.append {
      case NotAllowedModel(m) => () => Full(ForbiddenResponse("You don't have permissions to access " + m.getURI + "."))
    }

    if (Props.productionMode) {
      LiftRules.templateCache = Full(new InMemoryCache(500))
    }

    ResourceServer.allow {
      case ("enilink" | "typeaheadjs" | "require" | "orion" | "select2" | "fileupload" | "flight") :: _ => true
      case ("bootstrap" | "bootstrap-editable") :: _ | _ :: "bootstrap" :: _ => true
      case "material-design-iconic-font" :: _ => true
      case rdfa@"rdfa" :: _ if rdfa.last.endsWith(".js") => true
    }

    Globals.contextModelRules.append {
      case _ if S.param("model").isDefined => S.param("model").flatMap { name =>
        try {
          Full(URIs.createURI(name)).filterNot(_.isRelative)
        } catch {
          case e: Exception => error(e); Empty
        }
      }
    }

    val bnode = "^(_:.*)".r
    Globals.contextResourceRules.append {
      case _ if S.param("resource").isDefined => S.param("resource").flatMap {
        case bnode(id) => Full(new BlankNode(id))
        case other => try {
          Full(URIs.createURI(other)).filterNot(_.isRelative)
        } catch {
          case e: Exception => Empty
        }
      }
    }

    // a basic authentication method that can be used to login users using name and password
    val authentication = HttpBasicAuthentication("enilink") {
      case (username, password, _) =>
        Globals.contextModelSet.vend.map(ms => {
          val user = AccountHelper.findUser(ms.getMetaDataManager, username,
            AccountHelper.encodePassword(password))
          if (user != null) {
            // initialize global context user
            Globals.contextUser.request.set(user)
            true
          } else false
        }) openOr false
    }

    // Make a unit of work span the whole HTTP request
    S.addAround(new LoanWrapper {
      private object DepthCnt extends DynoVar[Boolean]

      def apply[T](f: => T): T = if (DepthCnt.is == Full(true)) f
      else DepthCnt.run(true) {
        Globals.contextModelSet.vend.map { modelSet =>
          val noAjax = S.request.exists(_.standardRequest_?)

          // store for later Ajax requests
          if (noAjax) Globals.contextModelSet.request.set(Full(modelSet))

          var unitsOfWork: Seq[IUnitOfWork] = Nil
          // start a unit of work for the current model set
          val uow = modelSet.getUnitOfWork
          unitsOfWork = unitsOfWork ++ List(uow)
          uow.begin()

          // run authentication with active unit of work
          if (!Globals.contextUser.request.set_?) {
            S.request.foreach(r => authentication.verified_?(r))
          }

          // create a subject that can be used for within system actions
          val subject: Box[Subject] = if (Globals.contextUser.request.set_?) {
            val user = Globals.contextUser.vend.getURI
            Full(new Subject(true, Collections.singleton(new EnilinkPrincipal(user)),
              Collections.emptySet(), Collections.emptySet()))
          } else Empty

          try {
            val model = S.request.flatMap(req => Globals.contextModelRules.toList.find(_.isDefinedAt(req)) match {
              case Some(f) => f(req)
              case _ => Empty
            }).flatMap(uri => Full(modelSet.getModel(uri, false))) or Globals.contextModel.vend filterNot (_ == null)

            val rdfContext = (S.request.flatMap(req => Globals.contextResourceRules.toList.find(_.isDefinedAt(req)) match {
              case Some(f) => f(req)
              case _ => Empty
            }).flatMap(ref => model.map(_.resolve(ref))) or model.map(_.getOntology)) map (RdfContext(_, null))

            if (noAjax) {
              if (model.isDefined) Globals.contextModel.request.set(model)
              if (rdfContext.isDefined) CurrentContext.forRequest(rdfContext)
            }

            def innerFunc = {
              try {
                // the attribute lookup may cause a NPE if called on session shutdown
                // since Lift initializes even unused sessions before shutting them down
                (S.containerSession.flatMap(_.attribute("javax.security.auth.subject") match {
                  case s: Subject => Full(s)
                  case _ => None
                }) or subject).map {
                  s =>
                    Subject.doAs(s, new PrivilegedAction[T] {
                      override def run: T = f
                    })
                } openOr f
              } catch {
                case _: Throwable => f
              }
            }

            if (model.isDefined) Globals.contextModel.doWith(model) {
              if (rdfContext.isDefined) CurrentContext.withValue(rdfContext)(innerFunc) else innerFunc
            }
            else if (rdfContext.isDefined) CurrentContext.withValue(rdfContext)(innerFunc)
            else innerFunc
          } finally {
            for (uow <- unitsOfWork) uow.end()
          }
        } openOr f
      }
    })
  }
}
