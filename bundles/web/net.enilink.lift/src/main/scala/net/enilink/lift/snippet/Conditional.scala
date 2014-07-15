package net.enilink.lift.snippet

import scala.xml.NodeSeq
import scala.xml.NodeSeq.seqToNodeSeq
import scala.xml.UnprefixedAttribute
import net.enilink.core.security.SecurityUtil
import net.enilink.komma.core.URIs
import net.enilink.lift.util.Globals
import net.liftweb.common.Box
import net.liftweb.common.Box.option2Box
import net.liftweb.common.Full
import net.liftweb.http.S
import net.liftweb.util.Helpers.tryo
import net.liftweb.common.Empty
import net.liftweb.util.Helpers._
import scala.xml.Elem
import scala.xml.MetaData
import scala.xml.Node

object Tests {
  def role(e: xml.Elem, param: String): Boolean = tryo(URIs.createURI(param)) exists { uri =>
    Globals.contextModelSet.vend exists { ms => SecurityUtil.hasRole(ms.getMetaDataManager, uri) }
  }

  def group(e: xml.Elem, param: String): Boolean = tryo(URIs.createURI(param)) exists { uri =>
    Globals.contextModelSet.vend exists { ms => SecurityUtil.isMemberOf(ms.getMetaDataManager, uri) }
  }

  def split(name: String) = name.indexOf('.') match {
    case -1 => (name, null)
    case n => (name.substring(0, n), name.substring(n + 1).trim)
  }

  def byName(name: String): Box[(xml.Elem, String) => Boolean] = split(name) match {
    case ("user", "loggedin") => Full((_, _) => Globals.contextUser.vend != Globals.UNKNOWN_USER)
    case ("user", "role") => Full(role _)
    case ("user", "group") => Full(group _)
    case ("param", null) => Full((_, value) => value.roboSplit(",").exists(p => S.param(p.stripPrefix("?")).exists(!_.trim.isEmpty)))
    case ("param", paramName) => Full((_, value) => value match {
      case "" => S.param(paramName).exists(!_.trim.isEmpty)
      case _ => S.param(paramName).exists(_ == value)
    })
    case _ => Empty
  }
}

trait ConditionalTransform {
  def evaluate(tests: Iterable[(String, String)], e: Elem, meta: MetaData, transform: Box[NodeSeq => NodeSeq] = Empty): NodeSeq
}

class If extends ConditionalTransform {
  def success(b: Boolean) = b

  def render(ns: NodeSeq): NodeSeq = {
    val tests = S.currentAttrs.collect { case UnprefixedAttribute(name, value, _) => (name, value.text) } ++ ns.flatMap {
      case e: xml.Elem => e.attributes.collect {
        case UnprefixedAttribute(name, value, _) if name.startsWith("data-") && name != "data-then" && name != "data-else" => (name.stripPrefix("data-"), value.text)
      }
      case _ => Nil
    }
    ns flatMap {
      case e: xml.Elem => evaluate(tests, e, e.attributes)
      case _ => Nil
    }
  }

  object Transform {
    def unapply(value: String): Option[(NodeSeq) => NodeSeq] = value.split("\\s*#>\\s*") match {
      case Array(left, right) => Some(left #> right)
      case _ => None
    }
  }

  override def evaluate(tests: Iterable[(String, String)], e: Elem, meta: MetaData, transform: Box[NodeSeq => NodeSeq] = Empty): NodeSeq = {
    def removeAttrs(meta: MetaData) = meta.remove("data-then").remove("data-else")
    lazy val thenRule = e.attribute("data-then")
    lazy val elseRule = e.attribute("data-else")

    // loop over all given tests and check if they pass according to the success function
    val matched = success(!tests.exists {
      case (name, value) => !Tests.byName(name.toLowerCase).exists(_(e, value))
    })

    lazy val hasRules = thenRule.nonEmpty || elseRule.nonEmpty
    val rule = if (matched) thenRule else elseRule
    rule.map(_.text) match {
      // TODO Should transform function also be applied to children in this case?
      case Some(Transform(f)) => f(e.copy(attributes = removeAttrs(meta)))
      case Some(_) => e.copy(attributes = removeAttrs(meta), child = transform.map(_(e.child)) openOr e.child)
      case None if matched || hasRules => e.copy(attributes = removeAttrs(e.attributes), child = transform.map(_(e.child)) openOr e.child)
      case _ => NodeSeq.Empty
    }
  }
}

class Unless extends If {
  override def success(b: Boolean) = !b

  override def render(ns: NodeSeq) = super.render(ns)
}