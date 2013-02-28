package net.enilink.lift.snippet

import scala.collection.JavaConversions.iterableAsScalaIterable
import scala.util.DynamicVariable
import scala.xml.Elem
import scala.xml.NodeSeq
import scala.xml.UnprefixedAttribute
import net.enilink.komma.model.ModelUtil
import net.enilink.komma.parser.manchester.ManchesterSyntaxGenerator
import net.enilink.lift.util.Globals
import net.enilink.lift.rdfa.template.RDFaTemplates
import net.liftweb.common.Box.box2Option
import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.common.Full
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.PageName
import net.liftweb.http.S
import net.liftweb.util.Helpers.strToCssBindPromoter
import net.liftweb.util.Helpers.tryo
import net.liftweb.util.IterableConst.itNodeSeqFunc
import net.liftweb.util.ClearNodes
import net.liftweb.util.Helpers
import net.liftweb.util.HttpHelpers
import net.liftweb.util.HttpHelpers
import scala.xml.NamespaceBinding
import scala.xml.TopScope
import net.enilink.komma.model.IObject
import net.enilink.komma.core.IReference

class RdfContext(val subject: Any, val predicate: Any, val prefix: NamespaceBinding = TopScope) {
  override def equals(that: Any): Boolean = that match {
    case other: RdfContext => subject == other.subject && predicate == other.predicate && prefix == other.prefix
    case _ => false
  }
  override def hashCode = (if (subject != null) subject.hashCode else 0) + (if (predicate != null) predicate.hashCode else 0) + prefix.hashCode

  override def toString = {
    "(s = " + subject + ", p = " + predicate + ", prefix = " + prefix + ")"
  }

  def copy(subject: Any = this.subject, predicate: Any = this.predicate, prefix: NamespaceBinding = this.prefix) = {
    new RdfContext(subject, predicate, prefix)
  }
}

object CurrentContext extends DynamicVariable[Box[RdfContext]](Empty)

class Rdf extends DispatchSnippet with RDFaTemplates {
  def dispatch: DispatchIt = {
    case method => CurrentContext.value match {
      case Full(c) => {
        (if (S.attr("for").exists(_ == "predicate")) c.predicate else c.subject) match {
          case null => ClearNodes
          case other => execMethod(other, method)
        }
      }
      // no current RDF context
      case _ => method match {
        // simply return template content
        case "label" | "manchester" => ((n: NodeSeq) => n)
        case _ => ClearNodes
      }
    }
  }

  private def execMethod(target: Any, method: String) = {
    (ns: NodeSeq) =>
      ns flatMap { n =>
        val replaceAttr = S.currentAttr("to")
        // support replacement of individual attributes
        if (replaceAttr.isDefined) {
          var attributes = n.attributes
          val attrValue = n.attribute(replaceAttr.get) getOrElse NodeSeq.Empty
          val value = method match {
            case "ref" => target.toString
            case "label" => ModelUtil.getLabel(target)
            case _ => ""
          }
          // encode if attribute is used as URL
          val encode = replaceAttr.get.toLowerCase match {
            case "href" | "src" => Helpers.urlEncode _
            case _ => (v: String) => v
          }

          var newAttrValue = attrValue.text.replaceAll("\\{\\}", encode(value))

          // insert current model into the attribute
          val modelName = Globals.contextModel.vend.dmap("")(_.toString)
          newAttrValue = newAttrValue.replaceAll("\\{model\\}", encode(modelName))
          val appPath = Globals.applicationPath.vend
          newAttrValue = newAttrValue.replaceAll("\\{app\\}", if (appPath == "/") "" else appPath)

          attributes = attributes.remove(replaceAttr.get)
          attributes = attributes.append(new UnprefixedAttribute(replaceAttr.get, newAttrValue, attributes))
          n.asInstanceOf[Elem].copy(attributes = attributes)
        } else {
          val selector = if (n.attributes.isEmpty || n.attributes.size == 1 && n.attribute("data-tid").isDefined) "*" else "* *"
          (method match {
            case "ref" => selector #> target.toString
            case "manchester" => selector #> new ManchesterSyntaxGenerator().generateText(target)
            case "label" => selector #> ModelUtil.getLabel(target)
            case _ => tryo(target.getClass.getMethod(method)) match {
              case Full(meth) => meth.invoke(target) match {
                case i: java.lang.Iterable[_] => selector #> i.map(withChangedContext _)
                case i: Iterable[_] => selector #> i.map(withChangedContext _)
                case null => ClearNodes
                case o @ _ => selector #> o.toString
              }
              case _ => ClearNodes
            }
          })(n)
        }
      }
  }

  def withChangedContext(s: Any)(n: NodeSeq): NodeSeq = {
    CurrentContext.withValue(Full(new RdfContext(s, null))) {
      S.session.get.processSurroundAndInclude(PageName.get, n)
    }
  }
}