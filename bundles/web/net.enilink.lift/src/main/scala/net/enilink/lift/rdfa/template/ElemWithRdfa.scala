package net.enilink.lift.rdfa.template
import scala.xml.MetaData
import scala.xml.Elem
import scala.xml.NamespaceBinding
import net.enilink.lift.snippet.RdfContext

object ElemWithRdfa {
  def unapplySeq(n: xml.Node) = n match {
    case e: ElemWithRdfa => Some((e.context, e.prefix, e.label, e.attributes, e.scope, e.child))
    case _ => None
  }
}

class ElemWithRdfa(
  val context: RdfContext,
  prefix: String,
  label: String,
  attributes: MetaData,
  scope: NamespaceBinding,
  child: xml.Node*) extends Elem(prefix, label, attributes, scope, child: _*) {

  override def basisForHashCode: Seq[Any] = List(context) ++ super.basisForHashCode

  override def copy(
    prefix: String = this.prefix,
    label: String = this.label,
    attributes: MetaData = this.attributes,
    scope: NamespaceBinding = this.scope,
    minimizeEmpty: Boolean = this.minimizeEmpty,
    child: Seq[xml.Node] = this.child.toSeq): ElemWithRdfa = new ElemWithRdfa(context, prefix, label, attributes, scope, child: _*)
}