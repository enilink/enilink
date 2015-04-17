package net.enilink.lift.snippet

import scala.xml.NodeSeq
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.LiftRules
import net.liftweb.http.S
import net.liftweb.util.Helpers._
import scala.xml.Text
import net.liftweb.sitemap.MenuItem
import net.liftweb.common.Full
import net.liftweb.sitemap.Loc
import net.enilink.lift.sitemap.Application
import net.liftweb.builtin.snippet.Msg
import net.enilink.lift.util.Globals
import net.enilink.lift.sitemap.HideIfInactive

object Bs extends DispatchSnippet {
  val alertAttrs = S.mapToAttrs(List("errorClass" -> "alert alert-danger",
    "warningClass" -> "alert", "noticeClass" -> "alert-info").toMap)
  val feedbackAttrs = S.mapToAttrs(List("errorClass",
    "warningClass", "noticeClass").map(_ -> "form-control-feedback").toMap)

  def dispatch: DispatchIt = {
    case "menu" => _ => menu
    case "submenu" => ns => submenu(ns)
    case "alert" => ns => S.withAttrs(alertAttrs)(Msg.render(ns))
    case "feedback" => ns => S.withAttrs(feedbackAttrs)(Msg.render(ns))
  }

  private def hidden(loc: Loc[_], path: List[Loc[_]]) = loc.hidden || loc.params.contains(HideIfInactive) && !path.contains(loc)

  private def menuEntries = {
    val result =
      (for {
        sm <- LiftRules.siteMap;
        req <- S.request;
        path = req.location match {
          case Full(loc) => loc.breadCrumbs
          case _ => Nil
        };
        app = Globals.application.vend getOrElse null
      } yield sm.kids.flatMap {
        // create only items for current application
        kid =>
          if (hidden(kid.loc, path)) Nil else kid.loc.currentValue match {
            case Full(someApp: Application) => if (someApp == app) {
              // skip application root locations
              if (kid.loc.link.uriList == app.path) kid.kids.flatMap(_.makeMenuItem(path)) else kid.makeMenuItem(path)
            } else Nil
            case _ => kid.makeMenuItem(path)
          }
      }) openOr Nil
    result
  }

  def menu: NodeSeq = {
    var i = 0

    def renderItem(item: MenuItem) = {
      i += 1

      var styles = item.cssClass openOr ""
      if (item.current || item.path) styles += " active"
      item.kids match {
        case Nil =>
          // item has no kids, show it as a link
          <li class={ styles }><a href={ item.uri }>{ item.text }</a></li>
        case kids if item.path =>
          // item has kids and is an ascendant of the currently selected item
          <li class={ styles }><a href={ if (item.placeholder_?) "#" else item.uri.text }>{ item.text }</a></li>
        case kids =>
          // render kids if item is currently not selected
          <li class={ styles + " dropdown" } id={ "menu" + i }>
            {
              if (item.placeholder_?) {
                <a href="#" data-target={ "#menu" + i } class="dropdown-toggle" data-toggle="dropdown">{ item.text }</a>
              } else {
                <a href={ item.uri }>
                  { item.text }
                  <div style="background-color:transparent; display: inline; padding: 10px 3px; margin: -5px 0" data-target={ "#menu" + i } class="dropdown-toggle" data-toggle="dropdown">
                    <b class="caret"></b>
                  </div>
                </a>
              }
            }
            <ul class="dropdown-menu">
              {
                for (kid <- kids) yield {
                  <li><a href={ kid.uri }>{ kid.text }</a></li>
                }
              }
            </ul>
          </li>
      }
    }
    val items = menuEntries
    def pullRight(item: MenuItem) = item.cssClass.exists(_ == "pull-right")
    <ul class="nav navbar-nav"> { for (item <- items.filterNot(pullRight(_))) yield renderItem(item) } </ul>
    <ul class="nav navbar-nav pull-right"> { for (item <- items.filter(pullRight(_))) yield renderItem(item) } </ul>
  }

  def submenu(ns: NodeSeq): NodeSeq = {
    menuEntries.find { e => e.path && !e.kids.isEmpty } match {
      case Some(item) => {
        ("ul *" #> {
          for (kid <- item.kids) yield {
            var styles = kid.cssClass openOr ""
            if (kid.current || kid.path) styles += " active"
            <li class={ styles }><a href={ kid.uri }>{ kid.text }</a></li>
          }
        }) apply (ns)
      }
      case _ => NodeSeq.Empty
    }
  }
}