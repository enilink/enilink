package net.enilink.lift.snippet

import scala.collection.JavaConversions.asScalaIterator
import scala.collection.mutable
import scala.xml.Elem
import scala.xml.NodeSeq
import scala.xml.NodeSeq.seqToNodeSeq
import scala.xml.UnprefixedAttribute

import net.enilink.komma.core.IBindings
import net.enilink.komma.core.IEntity
import net.enilink.komma.core.IGraph
import net.enilink.komma.core.IGraphResult
import net.enilink.komma.core.ITupleResult
import net.enilink.komma.core.LinkedHashBindings
import net.enilink.core.ModelSetManager
import net.enilink.lift.rdfa.template.RDFaTemplates
import net.enilink.lift.util.Globals
import net.liftweb.common.Box.box2Option
import net.liftweb.common.Full
import net.liftweb.http.PageName
import net.liftweb.http.S
import net.liftweb.util.ClearClearable

class Sparql extends RDFaTemplates {
  val selection = Globals.contextResource.vend.openOr(null)

  def render(n: NodeSeq): NodeSeq = {
    // check if inferred statements should be distinguished from explicit statements
    def distinguishInferred(ns: NodeSeq): Boolean = {
      ns.foldLeft(false) { (distInf, n) => distInf | (n \ "@data-if").text == "inferred" | distinguishInferred(n.child) }
    }
    val distInferred = distinguishInferred(n)

    def renderResults = {
      def toBindings(firstBinding: String, row: Any) = {
        row match {
          case b: IBindings[_] => b
          case other => {
            val b = new LinkedHashBindings[Any](1);
            b.put(firstBinding, other);
            b
          }
        }
      }

      lazy val (n1, sparql) = toSparql(n)
      CurrentContext.value.get.subject match {
        case entity: IEntity =>
          val query = entity.getEntityManager.createQuery(sparql).setParameter("this", entity)
          query.evaluate match {
            case r: IGraphResult =>
              n1 //renderGraph(new LinkedHashGraph(r.toList()))
            case r: ITupleResult[_] =>
              val firstBinding = r.getBindingNames.get(0)
              val allTuples = r.map { row => (toBindings(firstBinding, row), true) }
              val toRender = (if (distInferred) {
                // query explicit statements and prepend them to the results
                entity.getEntityManager.createQuery(sparql, false).setParameter("this", entity)
                  .evaluate.asInstanceOf[ITupleResult[_]]
                  .map { row => (toBindings(firstBinding, row), false) } ++ allTuples
              } else allTuples)
              val result = renderTuples(n1, toRender)
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

  def renderTuples(template: Seq[xml.Node], r: Iterator[(IBindings[_], Boolean)]) = {
    val existing = new mutable.HashMap[Key, Seq[xml.Node]]
    var result = r.foldLeft(Nil: NodeSeq)((transformed, row) => transform(CurrentContext.value.get, template)(row._1, row._2, existing))
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