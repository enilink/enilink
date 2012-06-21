package bootstrap.liftweb

import net.enilink.lift.sitemap.Application
import net.enilink.web.rest.ELSRest
import net.enilink.web.rest.ModelsRest
import net.liftweb.common.Full
import net.liftweb.http.LiftRules
import net.liftweb.http.LiftRulesMocker.toLiftRules
import net.liftweb.http.Req
import net.liftweb.http.S
import net.liftweb.http.auth.AuthRole
import net.liftweb.http.auth.HttpBasicAuthentication
import net.liftweb.http.auth.userRoles
import net.liftweb.sitemap.Loc.LinkText.strToLinkText
import net.liftweb.sitemap.LocPath.stringToLocPath
import net.liftweb.sitemap.Menu
import net.liftweb.sitemap.Menu.Menuable.toMenu
import net.liftweb.sitemap.SiteMap
import net.liftweb.sitemap.Loc._
import scala.xml.Text
import javax.security.auth.Subject
import net.liftweb.http.RedirectResponse

/**
 * A class that's instantiated early and run.  It allows the application
 * to modify lift's environment
 */
class LiftModule {
  object Right extends MenuCssClass("pull-right")
  
  def sitemapMutator: SiteMap => SiteMap = {
    val entries = List[Menu](Menu.i("enilink") / "" >> Application submenus (
      Menu("enilink.Home", S ? "Home") / "index",
      Menu("enilink.Vocabulary", S ? "Vocabulary") / "vocab",
      Menu("enilink.Login", S ? "Login") / "login" >> Right >> If(() => !loggedIn, S.??("already.loggedin")),
      Menu("enilink.Logout", S ? "Logout") / "logout" >> Right >> If(() => loggedIn, S.??("not.loggedin"))
      >> EarlyResponse(() => { logout; Full(RedirectResponse("/")) })))

    SiteMap.sitemapMutator { Map.empty }(SiteMap.addMenusAtEndMutator(entries))
  }

  def loggedIn = S.session.flatMap(_.httpSession.map(_.attribute("javax.security.auth.subject"))) match {
    case Full(_: Subject) => true
    case _ => false
  }

  def logout() { S.session.map(_.httpSession.map(_.removeAttribute("javax.security.auth.subject"))) }

  def boot {
    LiftRules.httpAuthProtectedResource.prepend {
      case Req("services" :: _, _, _) => Full(AuthRole("rest"))
    }

    LiftRules.authentication = HttpBasicAuthentication("enilink") {
      case ("rest", "client", req) => {
        userRoles(AuthRole("rest"))
        true
      }
    }

    LiftRules.dispatch.append(ELSRest)
    LiftRules.dispatch.append(ModelsRest)
  }
}
