package net.enilink.platform.lift.sitemap

import java.io.InputStream

import scala.xml.MetaData
import scala.xml.Node
import scala.xml.XML

import net.liftweb.sitemap.Loc
import net.liftweb.sitemap.Loc.Link
import net.liftweb.sitemap.Loc.LocParam
import net.liftweb.sitemap.Menu
import net.liftweb.sitemap.Menu.Menuable
import net.liftweb.sitemap.ConvertableToMenu
import scala.xml.Elem

/**
 * Helper for creating sitemaps from XML files
 */
object SiteMapXml {
  def parse(in: InputStream): List[Menu] = {
    val xml = XML.load(in)
    val menu = xml match {
      case sm @ <sitemap>{ _* }</sitemap> => {
        val app = sm \@ "app"
        val submenus = sm.child.flatMap {
          case loc @ <loc>{ _* }</loc> => Some(parseLocation(app, loc))
          case other => None // ignore
        }.toList
        Menus.application(app, app :: Nil, parseLocParams(sm.attributes), submenus)
      }
      case other => throw new IllegalArgumentException("Invalid sitemap element: " + other.label)
    }
    menu :: Nil
  }

  protected def parseLocParams[T](attributes: MetaData): List[LocParam[T]] = {
    attributes.toList.map(_.key).collect {
      case "hideIfInactive" => HideIfInactive
      case "hidden" => Loc.Hidden
    }
  }

  protected def parseLocation(app: String, loc: Node): ConvertableToMenu = {
    val text = (loc \@ "text")
    val name = (loc \@ "name") match {
      case n if n.nonEmpty => n
      case _ => text
    }
    val path = (loc \@ "path").split("/").toList match {
      // strippedPath may not be empty
      case "" :: (strippedPath @ (_ :: _)) => strippedPath
      case p if (app.isEmpty) => p
      case p => app :: p
    }
    val params = parseLocParams[Unit](loc.attributes)
    val submenus = loc.child.flatMap {
      case child @ <loc>{ _* }</loc> => Some(parseLocation(app, child))
      case other => None // ignore
    }.toList

    Menu(
      Loc(
        if (app.nonEmpty) app + "." + name else name,
        new Link(path, loc.attributes.find(_.key == "matchHead").isDefined),
        text, params),
      submenus: _*)
  }
}