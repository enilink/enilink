package bootstrap.liftweb

import java.security.AccessController
import java.security.PrivilegedAction
import net.enilink.komma.model.IModel
import net.enilink.komma.model.IObject
import net.enilink.komma.core.BlankNode
import net.enilink.komma.core.IUnitOfWork
import net.enilink.komma.core.URIImpl
import javax.security.auth.Subject
import net.enilink.auth.UserPrincipal
import net.enilink.core.ModelSetManager
import net.enilink.core.security.ISecureModelSet
import net.enilink.lift.util.Globals
import net.liftweb._
import net.liftweb.common._
import net.liftweb.common.Logger
import net.liftweb.http._
import net.liftweb.http.js.jquery.JQueryArtifacts
import net.liftweb.sitemap._
import net.liftweb.sitemap.Loc._
import net.liftweb.util._
import net.liftweb.util.Helpers._
import net.enilink.lift.util.NotAllowedModel
import net.enilink.lift.html.Html5ParserWithRDFaPrefixes

/**
 * A class that's instantiated early and run.  It allows the application
 * to modify lift's environment
 */
class LiftModule extends Logger {
  def boot {
    // set context user from UserPrincipal contained in the HTTP session after successful login
    Globals.contextUser.default.set(() => {
      Subject.getSubject(AccessController.getContext()) match {
        case s: Subject =>
          val userPrincipals = s.getPrincipals(classOf[UserPrincipal])
          if (!userPrincipals.isEmpty) userPrincipals.iterator.next.asInstanceOf[UserPrincipal].getId else Globals.UNKNOWN_USER
        case _ => Globals.UNKNOWN_USER
      }
    })

    //    LiftRules.resourceBundleFactories prepend {
    //      case (basename, locale) => ResourceBundle.getBundle(basename, locale)
    //    }

    // Use jQuery 1.8.2
    LiftRules.jsArtifacts = new JQueryArtifacts {
      override def pathRewriter: PartialFunction[List[String], List[String]] = {
        case "jquery.js" :: Nil if Props.devMode => List("jquery", "jquery-1.8.2.js")
        case "jquery.js" :: Nil => List("jquery", "jquery-1.8.2.min.js")
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
      new Html5Properties(r.userAgent).setHtmlParser(Html5ParserForRDFa.parse _))

    // Force the request to be UTF-8
    LiftRules.early.append(_.setCharacterEncoding("UTF-8"))

    // dispatch function for checking access to context model
    LiftRules.dispatch.append {
      case NotAllowedModel(m) => () => Full(ForbiddenResponse("You don't have permissions to access " + m.getURI + "."))
    }

    ResourceServer.allow {
      case (("require" | "orion" | "select2") :: _) => true
      case (("bootstrap" | "bootstrap-editable") :: _) | (_ :: "bootstrap" :: _) => true
      case rdfa @ ("rdfa" :: _) if rdfa.last.endsWith(".js") => true
    }

    // Make a unit of work span the whole HTTP request
    S.addAround(new LoanWrapper {
      private object DepthCnt extends DynoVar[Boolean]

      def apply[T](f: => T): T = if (DepthCnt.is == Full(true)) f
      else DepthCnt.run(true) {
        // a model and a resource can be passed by parameters
        val resourceName = S.param("resource")
        val modelName = S.param("model")

        var modelSet = ModelSetManager.INSTANCE.getModelSet
        var unitsOfWork: Seq[IUnitOfWork] = Nil
        // start a unit of work for the current model set
        var uow = modelSet.getUnitOfWork
        unitsOfWork = unitsOfWork ++ List(uow)
        uow.begin
        try {
          var model: Box[IModel] = Empty
          if (modelName.isDefined) {
            // try to get the model from the model set
            try {
              val modelUri = URIImpl.createURI(modelName.get)
              model = Box.legacyNullTest(modelSet.getModel(modelUri, false))
            } catch {
              case e: Exception => error(e)
            }
          }

          var resource: Box[AnyRef] = Empty
          if (model.isEmpty) {
            // try to get the model from the global selection (e.g. from a RAP instance)
            Globals.contextResource.vend.map(_ match {
              case selected: IObject => {
                resource = Full(selected)
                model = Full(selected.getModel)
              }
              case other: AnyRef => resource = Full(other)
              case _ => // leave resource empty
            })
          } else if (resourceName.isDefined) {
            // a resource was passed as parameter, replace the global selection with this resource
            val bnode = "^(_:.*)".r
            resourceName.get match {
              case bnode(id) => resource = Full(model.get.resolve(new BlankNode(id)))
              case _ => try {
                val resourceUri = URIImpl.createURI(resourceName.get)
                resource = Full(model.get.resolve(resourceUri))
              } catch {
                case e: Exception =>
              }
            }
          }
          // use corresponding ontology as resource
          if (model.isDefined && resource.isEmpty) {
            resource = Full(model.get.getOntology)
          }
          if (model.isDefined) {
            if (modelSet != model.get.getModelSet) {
              modelSet = model.get.getModelSet
              var uow = modelSet.getUnitOfWork
              unitsOfWork = unitsOfWork ++ List(uow)
              uow.begin
            }
          }
          Globals.contextResource.doWith(resource) {
            Globals.contextModel.doWith(model) {
              S.session.flatMap(_.httpSession.map(_.attribute("javax.security.auth.subject")) match {
                case Full(s: Subject) => Full(Subject.doAs(s, new PrivilegedAction[T] {
                  override def run = f
                }))
                case _ => Full(f)
              }).openTheBox
            }
          }
        } finally {
          for (uow <- unitsOfWork) uow.end
        }
      }
    })
  }
}
