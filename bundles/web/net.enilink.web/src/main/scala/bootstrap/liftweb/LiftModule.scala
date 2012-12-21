package bootstrap.liftweb

import net.enilink.lift.sitemap.Application
import net.enilink.lift.util.Globals
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
import net.liftweb.sitemap.Loc._
import net.liftweb.sitemap.Loc.LinkText.strToLinkText
import net.liftweb.sitemap.LocPath.stringToLocPath
import net.liftweb.sitemap.Menu
import net.liftweb.sitemap.Menu.Menuable.toMenu
import net.liftweb.sitemap.SiteMap
import net.liftweb.http.RedirectResponse
import net.liftweb.sitemap.Loc

/**
 * A class that's instantiated early and run.  It allows the application
 * to modify lift's environment
 */
class LiftModule {
  object Right extends MenuCssClass("pull-right")

  def sitemapMutator: SiteMap => SiteMap = {
    def profileText = Globals.contextUser.vend.getURI.localPart

    val entries = List[Menu](Menu.i("enilink") / "" >> Application submenus (
      Menu("enilink.Home", S ? "Home") / "index",
      Menu("enilink.Vocabulary", S ? "Vocabulary") / "vocab",
      Menu("enilink.Login", S ? "Login") / "login" >> Right >> If(() => !S.loggedIn_?, S.??("logged.in")),
      Menu("enilink.SignUp", S ? "Sign up") / "register" >> Right >> Hidden >> If(() => !S.loggedIn_?, S.??("logged.in")),
      Menu("enilink.Profile", profileText) / "static" / "profile" >> Right >> If(() => S.loggedIn_?, S.??("must.be.logged.in"))
      submenus (Menu("enilink.Logout", S ? "Logout") / "logout" >> EarlyResponse(() => { logout; Full(RedirectResponse("/")) })),
      // /static path to be visible
      Menu(Loc("Static", Link(List("static"), true, "/static/index"),
        "Static Content", Hidden))))

    SiteMap.sitemapMutator { Map.empty }(SiteMap.addMenusAtEndMutator(entries))
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
