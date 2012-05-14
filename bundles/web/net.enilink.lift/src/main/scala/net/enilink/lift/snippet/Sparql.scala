package net.enilink.lift.snippet

import scala.collection.JavaConversions.asScalaIterator
import scala.collection.mutable
import scala.xml.NodeSeq.seqToNodeSeq
import scala.xml.NodeSeq
import net.enilink.komma.core.IBindings
import net.enilink.komma.core.ITupleResult
import net.enilink.komma.core.IEntity
import net.enilink.komma.core.IGraph
import net.enilink.komma.core.IGraphResult
import net.enilink.komma.core.LinkedHashBindings
import net.enilink.lift.util.Globals
import net.enilink.lift.rdfa.template.RDFaTemplates
import net.liftweb.common.Box.box2Option
import net.liftweb.common.Full
import net.liftweb.http.S
import net.liftweb.http.PageName
import net.liftweb.util.ClearClearable
import net.enilink.core.ModelSetManager
import scala.xml.Elem
import net.liftweb.util.Helpers
import scala.xml.UnprefixedAttribute

class Sparql extends RDFaTemplates {
  val selection = Globals.contextResource.vend

  def render(n: NodeSeq): NodeSeq = {
    def renderResults = {
      lazy val (n1, sparql) = toSparql(n)
      CurrentContext.value.get.subject match {
        case entity: IEntity =>
          val query = entity.getEntityManager().createQuery(sparql).setParameter("this", entity)

          query.evaluate() match {
            case r: IGraphResult =>
              n1 //renderGraph(new LinkedHashGraph(r.toList()))
            case r: ITupleResult[_] =>
              val result = renderTuples(n1, r.map(row => row match {
                case b: IBindings[_] => b
                case other => {
                  val b = new LinkedHashBindings[Any](1);
                  b.put(r.getBindingNames().get(0), other);
                  b
                }
              }))
              result
            case _ => n1
          }
        case _ => n1
      }
    }

    val isMetaQuery = (S.attr("target") openOr null) == "meta"

    CurrentContext.value match {
      case Full(_) if !isMetaQuery => renderResults
      case _ =>
        val target = if (isMetaQuery) ModelSetManager.INSTANCE.getModelSet else selection
        target match {
          case resource: Any =>
            CurrentContext.withValue(Full(new RdfContext(resource, null))) {
              renderResults
            }
          case _ => n
        }
    }
  }

  def toSparql(n: NodeSeq): (NodeSeq, String) = {
    (n, n.head.child.foldLeft("")((q, c) => c match { case scala.xml.Text(t) => q + t case _ => q }))
  }

  def renderGraph(r: IGraph) = {
    "Test"
  }

  def renderTuples(template: Seq[xml.Node], r: Iterator[IBindings[_]]) = {
    val existing = new mutable.HashMap[Key, Seq[xml.Node]]
    var result = r.foldLeft(Nil: NodeSeq)((transformed, row) => transform(CurrentContext.value.get, template)(row, existing))
    result = ClearClearable.apply(S.session.get.processSurroundAndInclude(PageName.get, processSurroundAndInclude(result)))
    result.map(_ match {
      case e: Elem => {
        // add RDFa prefix declarations
        val xmlns = "xmlns:?([^=]+)=\"(\\S+)\"".r.findAllIn(e.scope.buildString(scala.xml.TopScope)).matchData.map(m => (m.group(1), m.group(2))).toList
        if (xmlns.isEmpty) e else {
          // add RDFa 1.1 prefix attribute
          var attributes = e.attributes.append(
            new UnprefixedAttribute("prefix", xmlns.foldLeft(new StringBuilder((e \ "@prefix").text)) {
              (sb, mapping) =>
                if (sb.length > 0) sb.append(" ")
                sb.append(mapping._1).append(": ").append(mapping._2)
            }.toString, e.attributes))
          // add legacy xmlns attributes
          attributes = xmlns.foldLeft(attributes) { (attrs, mapping) => attrs.append(new UnprefixedAttribute("xmlns:" + mapping._1, mapping._2, attrs)) }
          e.copy(attributes = attributes)
        }
      }
      case other => other
    })
  }
}