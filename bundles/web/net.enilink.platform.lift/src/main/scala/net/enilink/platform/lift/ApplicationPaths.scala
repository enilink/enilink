package net.enilink.platform.lift

import scala.xml.NodeSeq
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.LiftRules
import scala.xml.UnprefixedAttribute
import scala.xml.Node
import scala.xml.MetaData
import net.liftweb.builtin.snippet.Head
import scala.xml.Text
import scala.xml.Null
import scala.xml.Elem
import scala.xml.Group
import net.liftweb.http.LiftRulesMocker.toLiftRules
import scala.xml.NodeSeq.seqToNodeSeq

object ApplicationPaths {
  def rewriteApplicationPaths: Unit = {
    LiftRules.urlDecorate.append {
      case url => fixUrl(url)
    }

    def fixAttrValue(url: Seq[Node]): Seq[Node] = url map {
      case Text(t) => Text(fixUrl(t))
      case other => other
    }

    def fixAttrs(attrs: MetaData, toFix: String): MetaData = attrs match {
      case Null => Null
      case u: UnprefixedAttribute if u.key == toFix =>
        new UnprefixedAttribute(toFix, fixAttrValue(attrs.value), fixAttrs(attrs.next, toFix))
      case _ => attrs.copy(fixAttrs(attrs.next, toFix))
    }

    def fixNodeSeq(ns: NodeSeq): NodeSeq = {
      ns.flatMap {
        case Group(nodes) => Group(fixNodeSeq(nodes))
        case e: Elem if e.label == "script" => e.copy(attributes = fixAttrs(e.attributes, "src"))
        case e: Elem if e.label == "link" => e.copy(attributes = fixAttrs(e.attributes, "href"))
        case other => other
      }
    }

    def fixUrl(url: String): String = {
      // the magic should happen here
      println(url)
      url
    }

    object NewHead extends DispatchSnippet {
      def dispatch: DispatchIt = {
        case _ => render _
      }

      def render(ns: NodeSeq): NodeSeq = {
        Head.render(fixNodeSeq(ns))
      }
    }

    LiftRules.snippetDispatch.prepend {
      Map(
        "Head" -> NewHead,
        "head" -> NewHead)
    }
  }

}