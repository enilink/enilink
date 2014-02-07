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
import net.enilink.komma.em.concepts.IClass
import net.enilink.komma.core.ILiteral
import net.enilink.vocab.xmlschema.XMLSCHEMA
import java.text.NumberFormat
import java.util.Date
import java.text.DateFormat
import java.sql.Time
import java.util.Locale
import net.liftweb.http.LiftRules

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

  private def toLabel(target: Any, useLabelForVocab: Boolean = false) = target match {
    case _: IClass => toManchester(target)
    case l: ILiteral => l.getDatatype match {
      case XMLSCHEMA.TYPE_STRING | null => ModelUtil.getLabel(target, useLabelForVocab)
      case _ => Globals.contextModel.vend.map {
        lazy val locale = Locale.ENGLISH // TODO use S.locale if client-side (x-editable) supports localized editing
        _.getManager.toInstance(l) match {
          case n: Number => NumberFormat.getInstance(locale).format(n)
          case t: Time => DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(t)
          case d @ (_: Date | _: java.sql.Date) => DateFormat.getDateInstance(DateFormat.SHORT, locale).format(d)
          case other => other.toString
        }
      } openOr { ModelUtil.getLabel(target, useLabelForVocab) }
    }
    case _ => ModelUtil.getLabel(target, useLabelForVocab)
  }

  private def toManchester(target: Any) = new ManchesterSyntaxGenerator().generateText(target)

  private def execMethod(target: Any, method: String) = {
    def runMethod: PartialFunction[String, String] = {
      case "name" => target match {
        case ref: IReference if ref.getURI != null => ref.getURI.localPart
        case _ => target.toString
      }
      case "ref" => target.toString
      case "!label" => toLabel(target, true)
      case "label" => toLabel(target)
      case "manchester" => toManchester(target)
      case "get-url" => target match {
        case ref: IReference if ref.getURI != null && ref.getURI.scheme == "blobs" =>
          S.contextPath + "/files/" + ref.getURI.opaquePart
        case _ => target.toString
      }
    }
    (ns: NodeSeq) =>
      ns flatMap { n =>
        val replaceAttr = S.currentAttr("to")
        // support replacement of individual attributes
        if (replaceAttr.isDefined) {
          var attributes = n.attributes
          val attrValue = n.attribute(replaceAttr.get) getOrElse NodeSeq.Empty
          val value = method match {
            case m if runMethod.isDefinedAt(m) => runMethod(m)
            case _ => ""
          }
          
          val origText = if (attrValue.isEmpty) "{}" else attrValue.text
          
          // encode if attribute is used as URL
          val encode = replaceAttr.get.toLowerCase match {
            case "href" | "src" if origText != "{}" => Helpers.urlEncode _
            case _ => (v: String) => v
          }
          
          val newAttrValue = "\\{([^}]*)\\}".r.replaceAllIn(origText, m => m.group(1) match {
            case "" => encode(value)
            case "model" => {
              // insert current model into the attribute
              val modelName = Globals.contextModel.vend.map(_.toString) or Globals.contextResource.vend.map {
                case o: IObject => o.getModel.toString
                case _ => ""
              } openOr ""
              encode(modelName)
            }
            case "app" => Globals.applicationPath.vend.stripSuffix("/")
            case other => S.param(other).dmap("")(encode(_))
          })

          attributes = attributes.remove(replaceAttr.get)
          attributes = attributes.append(new UnprefixedAttribute(replaceAttr.get, newAttrValue, attributes))
          n.asInstanceOf[Elem].copy(attributes = attributes)
        } else {
          val selector = if (n.attributes.isEmpty || n.attributes.size == 1 && n.attribute("data-t").isDefined) "*" else "* *"
          (method match {
            case m if runMethod.isDefinedAt(m) => selector #> runMethod(m)
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