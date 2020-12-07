package net.enilink.platform.lift.snippet

import net.enilink.komma.core.{ILiteral, IReference}
import net.enilink.komma.em.concepts.IClass
import net.enilink.komma.model.{IObject, ModelUtil}
import net.enilink.komma.parser.manchester.ManchesterSyntaxGenerator
import net.enilink.platform.lift.rdfa.template.RDFaTemplates
import net.enilink.platform.lift.util.{CurrentContext, Globals}
import net.enilink.vocab.xmlschema.XMLSCHEMA
import net.liftweb.common.{Box, Empty, Full}
import net.liftweb.http.{DispatchSnippet, PageName, S}
import net.liftweb.util.Helpers._
import net.liftweb.util.{ClearNodes, Helpers, PassThru}

import java.sql.Time
import java.text.{DateFormat, NumberFormat, SimpleDateFormat}
import java.util.{Date, Locale}
import javax.xml.datatype.XMLGregorianCalendar
import scala.jdk.CollectionConverters._
import scala.xml.{Elem, NodeSeq, UnprefixedAttribute}

class Rdf extends DispatchSnippet with RDFaTemplates {
  def dispatch: DispatchIt = {
    case method => CurrentContext.value match {
      case Full(c) => {
        val target = if (S.attr("for").exists(_ == "predicate")) c.predicate else c.subject
        // target may also be null
        execMethod(target, method)
      }
      // no current RDF context
      case _ => method match {
        // simply return template content
        case "label" | "manchester" => ((n: NodeSeq) => n)
        case "model" => execMethod(null, method)
        case _ => ClearNodes
      }
    }
  }

  private def userFormat: Box[DateFormat] = S.attr("format") flatMap (f => tryo { new SimpleDateFormat(f) })

  private def toLabel(target: Any, useLabelForVocab: Boolean = false) = target match {
    case c: IClass if !useLabelForVocab || c.getRdfsLabel == null => toManchester(target)
    case l: ILiteral => l.getDatatype match {
      case XMLSCHEMA.TYPE_STRING | null => ModelUtil.getLabel(target, useLabelForVocab)
      case _ => Globals.contextModel.vend.map {
        lazy val locale = S.locale
        _.getManager.toInstance(l) match {
          case n: Number => NumberFormat.getInstance(Locale.ENGLISH).format(n) // TODO use S.locale if client-side (x-editable) supports localized editing
          case t: Time => (userFormat openOr DateFormat.getTimeInstance(DateFormat.SHORT, locale)).format(t)
          case d @ (_: Date | _: java.sql.Date) => (userFormat openOr DateFormat.getDateInstance(DateFormat.SHORT, locale)).format(d)
          case xgc: XMLGregorianCalendar => {
            val formatter = userFormat openOr DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, S.locale)
            formatter.setTimeZone(xgc.getTimeZone(xgc.getTimezone))
            formatter.format(xgc.toGregorianCalendar.getTime)
          }
          case other => other.toString
        }
      } openOr { ModelUtil.getLabel(target, useLabelForVocab) }
    }
    case _ => ModelUtil.getLabel(target, useLabelForVocab)
  }

  private def toManchester(target: Any) = new ManchesterSyntaxGenerator().generateText(target)

  private def execMethod(target: Any, method: String) = {
    def runMethod: PartialFunction[String, String] = {
      case "model" => target match {
        case o: IObject => o.getModel.toString
        case _ => Globals.contextModel.vend map (_.toString) openOr ""
      }
      case "pname" => ModelUtil.getPName(target)
      case "name" => target match {
        case ref: IReference if ref.getURI != null => ref.getURI.localPart
        case _ => String.valueOf(target)
      }
      case "ref" => String.valueOf(target)
      case "!label" => toLabel(target, true)
      case "label" => toLabel(target)
      case "manchester" => toManchester(target)
      case "get-url" => target match {
        case ref: IReference if ref.getURI != null && ref.getURI.scheme == "blobs" =>
          S.contextPath + "/files/" + ref.getURI.opaquePart
        case _ => String.valueOf(target)
      }
    }
    (ns: NodeSeq) =>
      ns flatMap { n =>
        S.currentAttr("to") match {
          case Full(replaceAttr) =>
            // support replacement of individual attributes
            var attributes = n.attributes
            val attrValue = n.attribute(replaceAttr) getOrElse NodeSeq.Empty
            val value = method match {
              case m if runMethod.isDefinedAt(m) => runMethod(m)
              case _ => ""
            }

            val origText = if (attrValue.isEmpty) "{}" else attrValue.text

            // encode if attribute is used as URL
            val encode = replaceAttr.toLowerCase match {
              case "href" | "src" if origText != "{}" => Helpers.urlEncode _
              case _ => (v: String) => v
            }

            val newAttrValue = "\\{([^}]*)\\}".r.replaceAllIn(origText, m => m.group(1) match {
              case "" => encode(value)
              case "this" => encode(CurrentContext.value.map(_.topContext.subject).filter(_ != null).map(_.toString) openOr "")
              case "model" => {
                // insert current model into the attribute
                encode(Globals.contextModel.vend.map(_.toString) openOr "")
              }
              case "app" => Globals.applicationPath.vend.stripSuffix("/")
              case other => (S.attr(other) or S.param(other)).dmap("")(encode(_))
            })

            attributes = attributes.remove(replaceAttr)
            attributes = attributes.append(new UnprefixedAttribute(replaceAttr, newAttrValue, attributes))
            n.asInstanceOf[Elem].copy(attributes = attributes)
          case Empty if target != null =>
            val selector = if (n.attributes.isEmpty || n.attributes.size == 1 && n.attribute("data-t").isDefined) "*" else "* *"
            lazy val hasChildren = n.nonEmptyChildren.nonEmpty
            (method match {
              case m if runMethod.isDefinedAt(m) => selector #> runMethod(m)
              case _ => tryo(target.getClass.getMethod(method)) match {
                case Full(meth) => meth.invoke(target) match {
                  case i: java.lang.Iterable[_] if hasChildren => selector #> i.asScala.map(withChangedContext _)
                  case i: Iterable[_] if hasChildren => selector #> i.map(withChangedContext _)
                  case null => PassThru
                  case o @ _ if hasChildren => selector #> withChangedContext(o) _
                  case o @ _ => selector #> o.toString
                }
                case _ => ClearNodes
              }
            })(n)
          case _ => n
        }
      }
  }

  def withChangedContext(s: Any)(n: NodeSeq): NodeSeq = CurrentContext.withSubject(s) {
    S.session.map(_.processSurroundAndInclude(PageName.get, n)) openOr Nil
  }
}