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
              renderTuples(n1, r.map(row => row match {
                case b: IBindings[_] => b
                case other => {
                  val b = new LinkedHashBindings[Any](1);
                  b.put(r.getBindingNames().get(0), other);
                  b
                }
              })) \ "_"
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
    (n, n.first.child.foldLeft("")((q, c) => c match { case scala.xml.Text(t) => q + t case _ => q }))
  }

  def renderGraph(r: IGraph) = {
    "Test"
  }

  def renderTuples(template: Seq[xml.Node], r: Iterator[IBindings[_]]) = {
    val existing = new mutable.HashMap[Key, Seq[xml.Node]]
    val result = r.foldLeft(Nil: NodeSeq)((transformed, row) => transform(CurrentContext.value.get, template)(row, existing))
    ClearClearable.apply(S.session.get.processSurroundAndInclude(PageName.get, processSurroundAndInclude(result)))
  }
}