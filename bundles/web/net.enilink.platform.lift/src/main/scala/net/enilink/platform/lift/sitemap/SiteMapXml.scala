package net.enilink.platform.lift.sitemap

import net.enilink.komma.core.{URI, URIs}
import net.liftweb.common.{Box, Empty, Full}
import net.liftweb.http.{RedirectResponse, Req}
import net.liftweb.sitemap.Loc.{EarlyResponse, Hidden, Link, LocParam, QueryParameters, UseParentParams}
import net.liftweb.sitemap.{ConvertableToMenu, Loc, Menu}

import java.io.InputStream
import scala.xml.{MetaData, Node, Text, XML}

case class ModelSpec(uri: Option[URI], location: Option[URI])

/**
 * Helper for creating sitemaps from XML files
 */
class SiteMapXml {
  protected var menuList: List[Menu] = Nil
  protected var modelList: List[ModelSpec] = Nil
  protected var modelRules: Map[List[String], URI] = Map.empty

  def menus: List[Menu] = menuList

  def models: List[ModelSpec] = modelList

  def contextModelRules : Box[PartialFunction[Req, Box[URI]]] = if (modelRules.isEmpty) Empty else {
    Full(new PartialFunction[Req, Box[URI]] {
      override def isDefinedAt(req: Req): Boolean = apply(req).isDefined

      override def apply(req: Req): Box[URI] = {
        var path = req.path.partPath
        var model : Option[URI] = None
        while (model.isEmpty && path.nonEmpty) {
          model = modelRules.get(path)
          if (model.isEmpty) path = path.dropRight(1)
        }
        model
      }
    })
  }

  def parse(in: InputStream) : Unit = {
    val xml = XML.load(in)
    menuList = xml match {
      case sm @ <sitemap>{ _* }</sitemap> =>
        val app = sm \@ "app"
        parseModelRule(app :: Nil, sm)

        modelList = sm.child.collect {
          case model @ <model/> => parseModelSpec(model)
        }.toList

        val submenus = sm.child.collect {
          case loc @ <loc>{ _* }</loc> => parseLocation(app, loc)
        }.toList

        // redirect to index
        val redirect = Menus.appMenu("redirect-to-index", "", Nil)(app) >> Hidden >> EarlyResponse(() => {
          Full(RedirectResponse(s"/$app/"))
        })
        redirect :: Menus.application(app, app :: Nil, parseLocParams(sm.attributes), submenus) :: Nil
      case other => throw new IllegalArgumentException("Invalid sitemap element: " + other.label)
    }
  }

  protected def parseLocParams[T](attributes: MetaData): List[LocParam[T]] = {
    attributes.toList.map(attr => (attr.key, attr.value)).collect {
      case ("hideIfInactive", _) => HideIfInactive
      case ("queryParameters", Seq(Text(v), _*)) =>
        val params = v.split('&').map(keyValue => {
          keyValue.split('=') match {
              // keyValue may contain multiple equal signs
            case Array(key, value @ _*) => (key, value.mkString("="))
          }
        }).toList
        QueryParameters(() => params)
      case ("useParentParams", _) => UseParentParams()
      case ("hidden", _) => Loc.Hidden
    }
  }

  protected def parseModelSpec(model: Node): ModelSpec = {
    ModelSpec(
      Option(model \@ "uri").filter(_.nonEmpty).map(URIs.createURI),
      Option(model \@ "location").filter(_.nonEmpty).map(URIs.createURI))
  }

  protected def parseModelRule(path : List[String], loc: Node) : Unit = {
    loc \@ "model" match {
      case m if m.nonEmpty => (try {
        Option(URIs.createURI(m)).filter(! _.isRelative)
      } catch {
        case e : Exception => None
      }) foreach (uri => {
        modelRules += (path -> uri)
      })
      case _ => // ignore
    }
  }

  protected def parseLocation(app: String, loc: Node): ConvertableToMenu = {
    val text = loc \@ "text"
    val name = loc \@ "name" match {
      case n if n.nonEmpty => n
      case _ => text
    }
    val path = (loc \@ "path").split("/").toList match {
      // strippedPath may not be empty
      case "" :: (strippedPath @ _ :: _) => strippedPath
      case p if app.isEmpty => p
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
        new Link(path, loc.attributes.exists(_.key == "matchHead")),
        text, params),
      submenus: _*)
  }
}