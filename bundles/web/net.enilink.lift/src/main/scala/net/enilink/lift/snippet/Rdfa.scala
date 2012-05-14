package net.enilink.lift.snippet

import scala.xml.Elem
import scala.xml.NodeSeq

import net.enilink.lift.rdfa.RDFaToSparql
import net.liftweb.util.Helpers._
import net.liftweb.util.Helpers

/**
 * Required to call super.render in EditRdfa trait
 */
trait HasRender {
  def render(n: NodeSeq) : NodeSeq 
}

trait EditRdfa extends HasRender {
  abstract override def render(n: NodeSeq): NodeSeq = {
    var tid = 0
     def withTemplateIds(n: NodeSeq): NodeSeq = n.flatMap {
      _ match {
        case e: Elem =>
          val newE = e.attribute("tid") match {
            case Some(id) => e
            case None => e % ("data-tid" -> {tid = tid + 1; tid})
          }
          if (newE.child.isEmpty) newE else newE.copy(child = withTemplateIds(newE.child))
        case other => other
      }
    }
    super.render(withTemplateIds(n))
  }
}

class Rdfa extends Sparql with EditRdfa {
  override def render(n: NodeSeq): NodeSeq = {
    super.render(n)
  }

  override def toSparql(n: NodeSeq): (NodeSeq, String) = {
    val result = new RDFaToSparql().toSparql(n.head.asInstanceOf[Elem], "http://example.org#")
    result
  }
}