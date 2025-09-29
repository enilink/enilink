package net.enilink.platform.web

import net.enilink.platform.lift.sitemap.{AddAppMenusAfter, HideIfInactive, KeepQueryParameters, Menus}
import net.enilink.platform.web.rest.{ModelsRest, SparqlRest}
import net.liftweb.common.Full
import net.liftweb.http.LiftRulesMocker.toLiftRules
import net.liftweb.http.{LiftRules, ParsePath, Req, RewriteRequest, RewriteResponse, S}
import net.liftweb.http.auth.{AuthRole, HttpBasicAuthentication, userRoles}
import net.liftweb.sitemap.Loc.{Hidden, Link}
import net.liftweb.sitemap.Loc.LinkText.strToLinkText
import net.liftweb.sitemap.LocPath.stringToLocPath
import net.liftweb.sitemap.{Loc, Menu, SiteMap}

/**
 * A class that's instantiated early and run.  It allows the application
 * to modify lift's environment
 */
class LiftModule {
  def sitemapMutator: SiteMap => SiteMap = {
    implicit val app: String = ""
    val entries = List[Menu](Menus.application("enilink", List(""), List(AddAppMenusAfter), List(
      Menu("enilink.Home", S ? "Home") / "index",
      Menu("enilink.Models", S ? "Models") / "models",
      // /upload for uploading of files
      Menu("enilink.Upload", S ? "Upload") / "upload" >> Hidden,
      // /static path to be visible
      Menu(Loc("Static", Link(List("static"), matchHead_? = true, "/static/index"),
        "Static Content", Hidden))) ++ Menus.userMenus)) ++
      Menus.globalMenus("describe", "describe", "describe" :: Nil, HideIfInactive, KeepQueryParameters())

    Menus.sitemapMutator(entries)
  }

  def boot() : Unit = {
    LiftRules.httpAuthProtectedResource.prepend {
      case Req("services" :: _, _, _) => Full(AuthRole("rest"))
    }

    LiftRules.authentication = HttpBasicAuthentication("enilink") {
      case ("rest", "client", req) =>
        userRoles(AuthRole("rest"))
        true
    }

    val sparqlRest = new SparqlRest
    // add models REST service
    LiftRules.dispatch.append(new ModelsRest(Some(sparqlRest)))
    // add SPARQL REST service
    LiftRules.dispatch.append(sparqlRest)

    // redirect to HTML presentation if requested by the client (e.g. for a browser)
    LiftRules.statelessRewrite.append({
      case RewriteRequest(
        ParsePath(("vocab" | "models") :: Nil, _, _, _), _, req) if req.param("model").nonEmpty &&
        req.param("type").isEmpty && req.headers("accept").exists(_.toLowerCase.contains("text/html")) =>
        RewriteResponse("static" :: "ontology" :: Nil)

      case RewriteRequest(ParsePath((prefix @ ("vocab" | "models")) :: first :: _, _, _, _), _, req) if first != "index" && req.param("model").isEmpty =>
        val params = Map("model" -> req.url)
        if (req.param("type").isEmpty && req.headers("accept").exists(_.toLowerCase.contains("text/html"))) {
          RewriteResponse("static" :: "ontology" :: Nil, params)
        } else {
          RewriteResponse(prefix :: Nil, params)
        }

      case RewriteRequest(
        ParsePath("sparql" :: Nil, _, _, _), _, req) if req.headers("accept").exists(_.toLowerCase.contains("text/html")) =>
        RewriteResponse("static" :: "sparql" :: Nil)
    })
  }
}
