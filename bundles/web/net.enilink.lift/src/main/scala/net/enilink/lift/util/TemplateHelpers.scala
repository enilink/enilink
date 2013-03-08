package net.enilink.lift.util

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

object TemplateHelpers {
  private object FindScript {
    def unapply(in: NodeSeq): Option[Elem] = in match {
      case e: Elem => e.attribute("type").map(_.text).filter(_ == "text/javascript").flatMap {
        a => if (e.attribute("src").isEmpty) Some(e) else None
      }
      case _ => None
    }
  }

  type RenderResult = (NodeSeq, Box[String])

  def render(path: List[String], snips: (String, NodeSeq => NodeSeq)*): Box[RenderResult] = {
    Templates(path) flatMap (render(_, snips: _*))
  }

  def render(template: NodeSeq, snips: (String, NodeSeq => NodeSeq)*): Box[RenderResult] = {
    S.eval(template, snips: _*) map { ns => S.session.map(_.fixHtml(ns)) openOr ns } map { ns =>
      import net.liftweb.util.Helpers._
      import scala.collection.mutable.ListBuffer
      val cmds = new ListBuffer[JsCmd]
      val revised = ("script" #> ((ns: NodeSeq) => {
        ns match {
          case FindScript(e) => {
            cmds += JE.JsRaw(ns.text).cmd
            NodeSeq.Empty
          }
          case x => x
        }
      }))(ns)
      (revised, if (cmds.nonEmpty) Full(cmds.reduceLeft(_ & _).toJsCmd) else Empty)
    }
  }

  def withTemplateNames(ns: NodeSeq): NodeSeq = {
    var currentNr = 0
    def process(ns: NodeSeq): NodeSeq = ns.flatMap {
      case e: Elem =>
        val newE = e.attribute("data-t") match {
          case Some(_) => e
          case None => e % ("data-t" -> { currentNr = currentNr + 1; "t" + currentNr })
        }
        if (newE.child.isEmpty) newE else newE.copy(child = process(newE.child))
      case other => other
    }
    process(ns)
  }

  def extractTemplate(ns: NodeSeq, name: String): NodeSeq = ns \\ "_" find { n => (n \ "@data-t").text == name } toSeq
}