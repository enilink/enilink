package net.enilink.platform.web

import net.enilink.platform.lift.sitemap.Application
import net.enilink.platform.lift.sitemap.Menus
import net.enilink.platform.lift.util.Globals
import net.enilink.platform.web.rest.ModelsRest
import net.liftweb.common.Full
import net.liftweb.http.GetRequest
import net.liftweb.http.LiftRules
import net.liftweb.http.LiftRulesMocker.toLiftRules
import net.liftweb.http.ParsePath
import net.liftweb.http.Req
import net.liftweb.http.RewriteRequest
import net.liftweb.http.RewriteResponse
import net.liftweb.http.S
import net.liftweb.http.auth.AuthRole
import net.liftweb.http.auth.HttpBasicAuthentication
import net.liftweb.http.auth.userRoles
import net.liftweb.sitemap.Loc
import net.liftweb.sitemap.Loc.Hidden
import net.liftweb.sitemap.Loc.Link
import net.liftweb.sitemap.Loc.LinkText.strToLinkText
import net.liftweb.sitemap.LocPath.stringToLocPath
import net.liftweb.sitemap.Menu
import net.liftweb.sitemap.Menu.Menuable.toMenu
import net.liftweb.sitemap.SiteMap
import net.enilink.platform.lift.sitemap.HideIfInactive
import net.enilink.platform.lift.sitemap.KeepQueryParameters
import net.enilink.platform.lift.sitemap.AddAppMenusAfter
import net.enilink.platform.web.rest.SparqlRest

/**
 * A class that's instantiated early and run.  It allows the application
 * to modify lift's environment
 */
class LiftModule {
  def sitemapMutator: SiteMap => SiteMap = {
    implicit val app = ""
    val entries = List[Menu](Menus.application("enilink", List(""), List(AddAppMenusAfter), List(
      Menu("enilink.Home", S ? "Home") / "index",
      Menu("enilink.Models", S ? "Models") / "models",
      // /upload for uploading of files
      Menu("enilink.Upload", S ? "Upload") / "upload" >> Hidden,
      // /static path to be visible
      Menu(Loc("Static", Link(List("static"), true, "/static/index"),
        "Static Content", Hidden))) ++ Menus.userMenus)) ++
      Menus.globalMenus("describe", "describe", "describe" :: Nil, HideIfInactive, KeepQueryParameters())

    Menus.sitemapMutator(entries)
  }

  def boot : Unit = {
    LiftRules.httpAuthProtectedResource.prepend {
      case Req("services" :: _, _, _) => Full(AuthRole("rest"))
    }

    LiftRules.authentication = HttpBasicAuthentication("enilink") {
      case ("rest", "client", req) => {
        userRoles(AuthRole("rest"))
        true
      }
    }

    LiftRules.dispatch.append(new ModelsRest())

    // redirect to HTML presentation if requested by the client (e.g. for a browser)
    LiftRules.statelessRewrite.append({
      case RewriteRequest(
        ParsePath(("vocab" | "models") :: Nil, _, _, _), _, req) if req.param("model").nonEmpty &&
        req.param("type").isEmpty && req.headers("accept").find(_.toLowerCase.contains("text/html")).isDefined =>
        RewriteResponse("static" :: "ontology" :: Nil)

      case RewriteRequest(ParsePath((prefix @ ("vocab" | "models")) :: first :: _, _, _, _), _, req) if first != "index" && req.param("model").isEmpty =>
        val params = Map("model" -> req.url)
        if (req.param("type").isEmpty && req.headers("accept").find(_.toLowerCase.contains("text/html")).isDefined) {
          RewriteResponse("static" :: "ontology" :: Nil, params)
        } else {
          RewriteResponse(prefix :: Nil, params)
        }

      case RewriteRequest(
        ParsePath("sparql" :: Nil, _, _, _), _, req) if req.headers("accept").find(_.toLowerCase.contains("text/html")).isDefined =>
        RewriteResponse("static" :: "sparql" :: Nil)
    })
    LiftRules.dispatch.append(SparqlRest)
  }
}
