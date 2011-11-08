package net.enilink.lift.snippet

import scala.util.DynamicVariable
import net.enilink.komma.core.IEntity
import net.enilink.komma.core.IReference
import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.common.Full
import net.liftweb.http.DispatchSnippet
import net.liftweb.util.Helpers._
import net.liftweb.util.ClearNodes
import net.enilink.komma.model.ModelUtil
import scala.collection.JavaConversions._
import scala.xml.NodeSeq
import net.liftweb.http.S
import net.liftweb.http.PageName
import net.enilink.lift.rdfa.template.RDFaTemplates

class RdfContext(var subject: Any, var predicate: Any) {
  override def equals(that: Any): Boolean = that match {
    case other: RdfContext => subject == other.subject && predicate == other.predicate
    case _ => false
  }
  override def hashCode = (if (subject != null) subject.hashCode else 0) + (if (predicate != null) predicate.hashCode else 0)
  
  override def toString = {
    "(s = " + subject + ", p = " + predicate + ")"
  }
}

object CurrentContext extends DynamicVariable[Box[RdfContext]](Empty)

class Rdf extends DispatchSnippet with RDFaTemplates {
  val VarAndMethod = "([^:]+):(.*)".r

  def dispatch: DispatchIt = {
    case VarAndMethod(v, method) => CurrentContext.value match {
      case Full(c) => v match {
        case "p" => execMethod(c.predicate, method)
        case _ => ClearNodes //TODO support access to variables
      }
      case _ => ClearNodes
    }
    case method => CurrentContext.value match {
      case Full(c) => execMethod(c.subject, method)
      case _ => ClearNodes
    }
  }

  private def execMethod(target: Any, method: String) = {
    (ns: NodeSeq) =>
      ns flatMap { n =>
        val selector = if (n.attributes.isEmpty) "*" else "* *"
        (method match {
          case "uri" => selector #> target.toString
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

  def withChangedContext(s: Any)(n: NodeSeq): NodeSeq = {
    CurrentContext.withValue(Full(new RdfContext(s, null))) {
      S.session.get.processSurroundAndInclude(PageName.get, n)
    }
  }
}