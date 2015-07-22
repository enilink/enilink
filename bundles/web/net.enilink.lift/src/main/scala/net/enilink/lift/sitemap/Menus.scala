package net.enilink.lift.sitemap

import scala.xml.Text

import net.enilink.lift.util.Globals
import net.liftweb.common.Empty
import net.liftweb.common.Full
import net.liftweb.common.StringFunc.strToStringFunc
import net.liftweb.http.LiftRules
import net.liftweb.http.LiftRulesMocker.toLiftRules
import net.liftweb.http.RedirectResponse
import net.liftweb.http.S
import net.liftweb.sitemap.*
import net.liftweb.sitemap.ConvertableToMenu
import net.liftweb.sitemap.Loc
import net.liftweb.sitemap.Loc.DataLoc
import net.liftweb.sitemap.Loc.EarlyResponse
import net.liftweb.sitemap.Loc.Hidden
import net.liftweb.sitemap.Loc.If
import net.liftweb.sitemap.Loc.Link
import net.liftweb.sitemap.Loc.LinkText
import net.liftweb.sitemap.Loc.LocParam
import net.liftweb.sitemap.Loc.MenuCssClass
import net.liftweb.sitemap.Loc.redirectToFailMsg
import net.liftweb.sitemap.Loc.strToFailMsg
import net.liftweb.sitemap.LocPath
import net.liftweb.sitemap.LocPath.stringToLocPath
import net.liftweb.sitemap.Menu
import net.liftweb.sitemap.Menu.Menuable
import net.liftweb.sitemap.SiteMap

/**
 * Helper functions for application menus in enilink.
 */
object Menus {
  val ENILINK_APPLICATION = Application("enilink", "" :: Nil)

  def application(name: String, path: List[String], submenus: List[ConvertableToMenu] = Nil): Menu = application(name, path, Nil, submenus)

  def application(name: String, path: List[String], params: List[Loc.LocParam[Application]], submenus: List[ConvertableToMenu]): Menu = {
    Menu(DataLoc(name, new Link(path), LinkText[Application](app => Text(app.name)), Full(Application(name, path)), params: _*), submenus: _*)
  }

  private object Right extends MenuCssClass("pull-right")
  private def locId(name: String)(implicit app: String) = if (app == "") name else app + "." + name

  def appMenu(name: String, linkText: Loc.LinkText[Unit], path: String*)(implicit app: String): Menuable = appMenu(name, linkText, path.toList)

  def appMenu(name: String, linkText: Loc.LinkText[Unit], path: List[String])(implicit app: String): Menuable = {
    val m = Menu(locId(name), linkText)
    val mPath = if (app == "") path else app :: path
    mPath.tail.foldLeft(m / mPath.head)(_ / _)
  }

  def userMenus(implicit app: String) = {
    def profileText = Globals.contextUser.vend.getURI.localPart
    def logout {
      S.session.map(_.httpSession.map(_.removeAttribute("javax.security.auth.subject")))
      Globals.contextUser.session.remove
    }

    List(appMenu("Login", S ? "Login", "login" :: Nil) >> Right >> If(() => !S.loggedIn_?, RedirectResponse("/")),
      appMenu("SignUp", S ? "Sign up", "register" :: Nil) >> Right >> Hidden,
      appMenu("Profile", profileText, "profile" :: Nil) >> Right >> If(() => S.loggedIn_?, S.?("must.be.logged.in")) submenus
        (appMenu("Logout", S ? "Logout", "logout" :: Nil) >> EarlyResponse(() => { logout; Full(RedirectResponse(calcHref("/"))) }))) //
  }

  def calcHref(path: String) = Globals.applicationPath.vend ++ path.stripPrefix("/")

  def globalMenus(name: String, linkText: Loc.LinkText[Application], path: List[String], params: LocParam[Application]*): List[Menu] = {
    List(
      // a global menu for any application
      new Menu.ParamMenuable[Application]("star." + name, linkText, nameParam => {
        LiftRules.siteMap.flatMap(_.findLoc(nameParam)).flatMap(_.currentValue match {
          case Full(app: Application) => Full(app)
          case _ => Empty
        })
      }, app => app.path.mkString("/"), * :: path.map(LocPath.stringToLocPath(_)), false, params.toList, Nil).toMenu,
      // the menu for the enilink application
      Menu(DataLoc("enilink." + name, new Link(path), linkText, Full(ENILINK_APPLICATION), params: _*)))
  }

  def sitemapMutator(menus: List[Menu], app: String): SiteMap => SiteMap = {
    val TheApp = app
    val MenuAfter = SiteMap.buildMenuMatcher { case AddMenusAfter(TheApp) => true case _ => false}
    val MenuUnder = SiteMap.buildMenuMatcher { case AddMenusUnder(TheApp) => true case _ => false}
    SiteMap.sitemapMutator {
      case MenuAfter(menu) => menu :: menus
      case MenuUnder(menu) => List(menu.rebuild(_ ::: menus))
    }(s => s)
  }

  private val AppMenuAfter = SiteMap.buildMenuMatcher(_ == AddAppMenusAfter)
  private val AppMenuUnder = SiteMap.buildMenuMatcher(_ == AddAppMenusUnder)

  def sitemapMutator(menus: List[Menu]): SiteMap => SiteMap = SiteMap.sitemapMutator {
    case AppMenuAfter(menu) => menu :: menus
    case AppMenuUnder(menu) => List(menu.rebuild(_ ::: menus))
  }(SiteMap.addMenusAtEndMutator(menus))
}