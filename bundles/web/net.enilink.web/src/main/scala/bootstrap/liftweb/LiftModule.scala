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

/**
 * A class that's instantiated early and run.  It allows the application
 * to modify lift's environment
 */
class LiftModule {
  def sitemapMutator: SiteMap => SiteMap = {
    val entries = List[Menu](Menu.i("Home") / "index" >> Application)

    SiteMap.sitemapMutator { Map.empty }(SiteMap.addMenusAtEndMutator(entries))
  }

  def boot {
    // do nothing for now
  }
}
