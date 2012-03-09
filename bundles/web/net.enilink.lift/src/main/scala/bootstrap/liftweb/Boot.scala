package bootstrap.liftweb

import net.enilink.komma.model.IModel
import net.enilink.komma.model.IObject
import net.enilink.komma.core.URIImpl
import net.enilink.core.ModelSetManager
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.sitemap.Loc._
import net.liftweb.sitemap._
import net.liftweb.util.Helpers._
import net.liftweb.util._
import net.liftweb._
import net.enilink.lift.lib.Globals
import net.enilink.komma.util.UnitOfWork
import net.enilink.komma.core.IUnitOfWork
import net.enilink.komma.core.BlankNode

/**
 * A class that's instantiated early and run.  It allows the application
 * to modify lift's environment
 */
class Boot {
  def boot {
    // where to search snippet
    LiftRules.addToPackages("net.enilink.lift")

    LiftRules.calculateContextPath = () => Full("/lift")

    //    LiftRules.resourceBundleFactories prepend {
    //      case (basename, locale) => ResourceBundle.getBundle(basename, locale)
    //    }

    def sitemap(): SiteMap = SiteMap(Menu.i("Home") / "index", // more complex because this menu allows anything in the
      // /static path to be visible
      Menu(Loc("Static", Link(List("static"), true, "/static/index"),
        "Static Content")))

    // set the sitemap.  Note if you don't want access control for
    // each page, just comment this line out.
    LiftRules.setSiteMapFunc(sitemap)

    // Use jQuery 1.4
    LiftRules.jsArtifacts = net.liftweb.http.js.jquery.JQuery14Artifacts

    //Show the spinny image when an Ajax call starts
    LiftRules.ajaxStart =
      Full(() => LiftRules.jsArtifacts.show("ajax-loader").cmd)

    // Make the spinny image go away when it ends
    LiftRules.ajaxEnd =
      Full(() => LiftRules.jsArtifacts.hide("ajax-loader").cmd)

    // Force the request to be UTF-8
    LiftRules.early.append(_.setCharacterEncoding("UTF-8"))

    // What is the function to test if a user is logged in?
    LiftRules.loggedInTest = Full(() => true) // TODO user is simply regarded as logged in

    // Use HTML5 for rendering
    LiftRules.htmlProperties.default.set((r: Req) =>
      new Html5Properties(r.userAgent))

    ResourceServer.allow {
      case bs @ ("bootstrap" :: _) if bs.last.endsWith(".css") || bs.last.endsWith(".png") || bs.last.endsWith(".js") => true
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
        try {
          var model: Box[IModel] = Empty
          if (modelName.isDefined) {
            // try to get the model from the model set
            try {
              val modelUri = URIImpl.createURI(modelName.get)
              // start a unit of work to retrieve the meta data
              var uow = modelSet.getUnitOfWork
              unitsOfWork = unitsOfWork ++ List(uow)
              uow.begin
              model = Full(modelSet.getModel(modelUri, true))
            } catch {
              case e: Exception => System.out.println(e)
            }
          }

          var resource: Option[AnyRef] = Empty
          if (model.isEmpty) {
            // try to get the model from the global selection (e.g. from a RAP instance)
            Globals.contextResource.vend match {
              case selected: IObject => {
                resource = Full(selected)
                model = Full(selected.getModel)
              }
              case selected: AnyRef => resource = Full(selected)
              case _ =>
            }
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
          if (resource.isDefined) {
            Globals.contextResource.request.set(resource.get)
          }
          if (model.isDefined) {
            Globals.contextModel.request.set(model.get)
          }

          if (model.isDefined && modelSet != model.get.getModelSet) {
            modelSet = model.get.getModelSet
            var uow = modelSet.getUnitOfWork
            unitsOfWork = unitsOfWork ++ List(uow)
            uow.begin
          }

          f
        } finally {
          for (uow <- unitsOfWork) uow.end
        }
      }
    })
  }
}
