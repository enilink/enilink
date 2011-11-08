package net.enilink.lift.snippet
import scala.collection.JavaConversions.asScalaBuffer
import scala.collection.JavaConversions.asScalaIterator
import scala.collection.mutable
import scala.xml.Elem
import scala.xml.Node
import scala.xml.NodeSeq
import net.enilink.komma.core.IBindings
import net.enilink.komma.core.IEntity
import net.enilink.komma.core.IGraph
import net.enilink.komma.core.IGraphResult
import net.enilink.komma.core.ITupleResult
import net.enilink.lift.eclipse.SelectionHolder
import net.enilink.lift.rdfa.RDFaToSparql
import net.liftweb.common.Box
import net.liftweb.common.Full
import net.liftweb.util.Helpers.pairToUnprefixed
import net.liftweb.util.Helpers.strToCssBindPromoter
import net.liftweb.util.Helpers.strToSuperArrowAssoc
import net.enilink.komma.core.LinkedHashBindings
import net.enilink.lift.rdfa.template.RDFaTemplates

class Sparql extends RDFaTemplates {
  val selection = SelectionHolder.getSelection()

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

    CurrentContext.value match {
      case Full(_) => renderResults
      case _ =>
        selection match {
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
    processSurroundAndInclude(result)
  }
}