package bootstrap.liftweb

import net.liftweb._
import util._
import Helpers._
import common._
import http._
import sitemap._
import Loc._
import net.liftweb.db.StandardDBVendor
import net.liftweb.db.DB
import java.util.ResourceBundle
import net.enilink.lift.eclipse.SelectionHolder

/**
 * A class that's instantiated early and run.  It allows the application
 * to modify lift's environment
 */
class Boot {
  def boot {
    // where to search snippet
    LiftRules.addToPackages("net.enilink.lift")

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

    // Make a unit of work span the whole HTTP request
    S.addAround(new LoanWrapper {
      private object DepthCnt extends DynoVar[Boolean]

      def apply[T](f: => T): T = if (DepthCnt.is == Full(true)) f
      else DepthCnt.run(true) {
        val selected = SelectionHolder.getSelection()
        if (selected != null) {
          val uow = selected.getModel().getModelSet().getUnitOfWork()

          try {
            uow.begin()
            f
          } finally {
            uow.end()
          }
        } else {
          f
        }
      }
    })
  }
}
