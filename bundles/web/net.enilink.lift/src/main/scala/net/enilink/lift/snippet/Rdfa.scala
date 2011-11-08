package net.enilink.lift.snippet
import net.enilink.lift.rdfa.RDFaToSparql
import scala.xml.NodeSeq
import scala.xml.Elem

class Rdfa extends Sparql {
  override def render(n : NodeSeq) : NodeSeq = {
    super.render(n)
  }
  
  override def toSparql(n: NodeSeq): (NodeSeq, String) = {
    val result = new RDFaToSparql().toSparql(n.first.asInstanceOf[Elem], "http://example.org#")
    result
  }
}