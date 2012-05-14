package bootstrap.liftweb

import net.liftweb.common._
import net.liftweb.http.js.jquery.JQueryArtifacts
import net.liftweb.http._
import net.liftweb.sitemap.Loc._
import net.liftweb.sitemap._
import net.liftweb.util.Helpers._
import net.liftweb.util._
import net.liftweb._
import net.enilink.lift.sitemap.Application
import net.enilink.web.rest.ELSRest
import net.enilink.web.rest.ModelsRest

/**
 * A class that's instantiated early and run.  It allows the application
 * to modify lift's environment
 */
class LiftModule {
  def sitemapMutator: SiteMap => SiteMap = {
    val entries = List[Menu](Menu.i("enilink") / "" >> Application submenus (
      Menu("enilink.Home", S ? "Home") / "index",
      Menu("enilink.Vocabulary", S ? "Vocabulary") / "vocab"))

    SiteMap.sitemapMutator { Map.empty }(SiteMap.addMenusAtEndMutator(entries))
  }

  def boot {
    LiftRules.dispatch.append(ELSRest)
    LiftRules.dispatch.append(ModelsRest)
  }
}
