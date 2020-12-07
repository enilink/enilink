package net.enilink.platform.lift.util

import scala.xml.NodeSeq
import net.liftweb.common.Box
import net.liftweb.http.Templates
import net.liftweb.http.js.JsCmd
import net.liftweb.common.Empty
import net.liftweb.common.Full
import net.liftweb.http.S
import scala.xml.Group
import scala.xml.Elem
import net.liftweb.http.js.JE
import net.liftweb.util.Helpers._
import net.liftweb.util.LiftFlowOfControlException
import net.liftweb.http.ParsePath
import net.liftweb.http.LiftRules
import net.enilink.platform.lift.sitemap.Application
import net.liftweb.sitemap.Loc
import scala.xml.Node
import net.liftweb.common.Failure
import net.liftweb.util.Props

object TemplateHelpers {
  type RenderResult = (NodeSeq, Box[String])

  def appFor(path: List[String]): Box[Application] = {
    // try to determine current application based on the given template path
    if (S.location.isEmpty && path.length > 1) {
      for (
        req <- S.request;
        newReq = req.withNewPath(ParsePath(path(0) :: Nil, "", true, false));
        loc <- LiftRules.siteMap.flatMap(_.findLoc(newReq));
        app <- loc.breadCrumbs.find(_.currentValue.exists(_.isInstanceOf[Application])).flatMap(_.currentValue.map(_.asInstanceOf[Application]))
      ) yield app
    } else Globals.application.vend
  }

  def withAppFor[F](path: List[String])(f: => F): F = appFor(path) match {
    case app @ Full(_) => Globals.application.doWith(app)(f)
    case _ => f
  }

  def find(path: List[String], name: Option[String] = Empty): Box[NodeSeq] = withAppFor(path) {
    template(path) flatMap { ns =>
      // extract template if a name is supplied
      if (name.isDefined) name flatMap (find(ns, _)) else Full(ns)
    }
  }

  def find(ns: NodeSeq, name: String): Box[NodeSeq] = {
    var template: Box[Node] = Empty
    tryo {
      S.eval(ns, ("rdfa", ns => {
        extractTemplate(withTemplateNames(ns), name) match {
          case t @ Some(_) =>
            template = t
            throw new LiftFlowOfControlException("Found template")
          case _ => ns
        }
      }))
    }
    // fallback: global search for template
    template or extractTemplate(withTemplateNames(ns), name)
  }

  def template(path: List[String]) = Templates("templates-hidden" :: path) match {
    case Full(x) => Full(x)
    case f: Failure if Props.devMode => f
    case _ => Templates(path)
  }

  def render(path: List[String], snips: (String, NodeSeq => NodeSeq)*): Box[RenderResult] = withAppFor(path) {
    template(path) flatMap (render(_, snips: _*))
  }

  private object FindScript {
    def unapply(in: NodeSeq): Option[Elem] = in match {
      case e: Elem if e.attribute("src").isEmpty => e.attribute("type").map(_.text) match {
        case None | Some("text/javascript") => Some(e)
        case _ => None
      }
      case _ => None
    }
  }

  def render(template: NodeSeq, snips: (String, NodeSeq => NodeSeq)*): Box[RenderResult] = {
    S.eval(template, snips: _*) map { ns => S.session.map(_.normalizeHtml(ns)) openOr ns } map { ns =>
      import net.liftweb.util.Helpers._
      import scala.collection.mutable.ListBuffer
      val cmds = new ListBuffer[JsCmd]
      val revised = ("script" #> ((ns: NodeSeq) => {
        ns match {
          case FindScript(e) => {
            cmds += JE.JsRaw(ns.text).cmd
            NodeSeq.Empty
          }
          case other => other
        }
      })).apply(ns)
      (revised, if (cmds.nonEmpty) Full(cmds.reduceLeft(_ & _).toJsCmd) else Empty)
    }
  }

  def withTemplateNames(ns: NodeSeq): NodeSeq = {
    val prefix = (ns \ "@data-t").text match {
      case name if !name.isEmpty => name + "/"
      case _ => ""
    }
    var currentNr = 0
    def process(ns: NodeSeq): NodeSeq = ns flatMap {
      case e: Elem =>
        val newE = e.attribute("data-t") match {
          case Some(_) => e
          case None => e % ("data-t" -> { currentNr = currentNr + 1; prefix + currentNr })
        }
        if (newE.child.isEmpty) newE else newE.copy(child = process(newE.child))
      case other => other
    }
    process(ns)
  }

  def extractTemplate(ns: NodeSeq, name: String): Option[Node] = ns \\ "_" find { n => (n \ "@data-t").text == name }
}