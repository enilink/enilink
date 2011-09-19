package net.enilink.lift.rdfa

/**
 * RDF abstract syntax per <cite><a
 * href="http://www.w3.org/TR/2004/REC-rdf-concepts-20040210/"
 * >Resource Description Framework (RDF):
 * Concepts and Abstract Syntax</a></cite>
 * W3C Recommendation 10 February 2004
 */
trait RDFGraphParts {
  /**
   * The spec calls them triples, but that seems to be muddled terminology.
   * Let's stick to traditional graph theory terminology here;
   * See rdflogic for mapping to logic terminology and semantics.
   *
   * Use Set[Arc], Stream[Arc], etc. as appropriate; note Iterable[Arc]
   * includes both Set and Stream.
   *
   */
  type Arc = (SubjectNode, Label, Node)

  type Node
  type Literal <: Node
  type SubjectNode <: Node
  type BlankNode <: SubjectNode
  type Label <: SubjectNode
  type LanguageTag = Symbol

}

/**
 * No concrete method for building blankNodes, as it
 * depends on the concrete syntax.
 */
trait RDFNodeBuilder extends RDFGraphParts {
  val rdf_type = uri(Vocabulary.`type`)
  val rdf_nil = uri(Vocabulary.nil)
  val rdf_first = uri(Vocabulary.first)
  val rdf_rest = uri(Vocabulary.rest)

  def uri(i: String): Label
  def plain(s: String, lang: Option[LanguageTag]): Literal
  def typed(s: String, dt: String): Literal
  def xmllit(content: scala.xml.NodeSeq): Literal
}

/**
 * RDF has only ground, 0-ary function terms.
 */
abstract class Ground extends FunctionTerm {
  import Term.Subst

  override def fun = this
  override def args = Nil
  override def variables = Set()
  override def subst(s: Subst) = this
}

case class Name(n: String) extends Ground
case class Plain(s: String, lang: Option[Symbol]) extends Ground
case class Data(lex: String, dt: Name) extends Ground
case class XMLLit(content: scala.xml.NodeSeq) extends Ground

/**
 * Logical variables for RDF.
 * @param n: an XML name. TODO: assert this
 */
case class XMLVar(val n: String, val qual: Option[Int]) extends Variable {
  // TODO: mix in Quotable
  def quote() = sym

  lazy val sym = qual match {
    case None => Symbol(n)
    case Some(x) => Symbol(n + "." + x)
  }
}

/**
 * Implement RDF Nodes (except BlankNode) using FOL function terms
 */
trait TermNode extends RDFNodeBuilder {
  type Node = Term
  type SubjectNode = Term
  type Label = Name

  def uri(i: String) = Name(i)

  type Literal = Term
  def plain(s: String, lang: Option[Symbol]) = Plain(s, lang)
  def typed(s: String, dt: String): Literal = Data(s, Name(dt))
  def xmllit(e: scala.xml.NodeSeq): Literal = XMLLit(e)
}

class Scope(val vars: Iterable[Variable]) {
  def this() = this(List())

  import scala.collection.mutable
  val varstack = new mutable.Stack[XMLVar]
  vars foreach {
    case v @ XMLVar(n, x) if safeName(n) == n => varstack.push(v)
    case v @ XMLVar(n, x) => varstack.push(fresh(n))
    case _ => varstack.push(fresh("v"))
  }

  /* baseName is a name that does *not* follow the xyx.123 pattern */
  protected def safeName(name: String) = {
    val lastChar = name.substring(name.length - 1)
    if ("0123456789".contains(lastChar) &&
      name.contains('.')) name + "_"
    else name
  }

  /**
   * Return a XMLVar for this name, creating one if necessary.
   * @return: the same XMLVar given the same name.
   */
  def byName(name: String): XMLVar = {
    var safe = safeName(name)
    varstack.find { v => v.quote().name == safe } match {
      case None => fresh(safe)
      case Some(v) => v
    }
  }

  /**
   * @param suggestedName: an XML name
   * @return an XML name unique to this scope
   */
  def fresh(suggestedName: String): XMLVar = {
    assert(suggestedName.length > 0)

    val baseName = safeName(suggestedName)

    val b = {
      val seen = varstack.exists { v => v.quote().name == baseName }
      if (seen) XMLVar(baseName, Some(varstack.size))
      else XMLVar(baseName, None)
    }

    varstack.push(b)
    b
  }
}