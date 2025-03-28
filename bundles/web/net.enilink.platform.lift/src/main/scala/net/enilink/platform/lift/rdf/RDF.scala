package net.enilink.platform.lift.rdf
import scala.collection.mutable
import scala.xml.NamespaceBinding

/**
 * RDF abstract syntax per <cite><a
 * href="http://www.w3.org/TR/2004/REC-rdf-concepts-20040210/"
 * >Resource Description Framework (RDF):
 * Concepts and Abstract Syntax</a></cite>
 * W3C Recommendation 10 February 2004
 */
trait Node
trait Literal extends Node
case class PlainLiteral(s: String, lang: Option[Symbol]) extends Literal
case class TypedLiteral(lex: String, dt: Label) extends Literal
case class XmlLiteral(content: scala.xml.NodeSeq) extends Literal

trait Reference extends Node
case class Label(n: String) extends Reference {
  override def toString: String = n
}
case class BlankNode() extends Reference

case class Variable(n: String, qual: Option[Int]) extends Reference {
  def quote(): Symbol = sym

  lazy val sym: Symbol = qual match {
    case None => Symbol(n)
    case Some(x) => Symbol(n + "_" + x)
  }

  override def toString: String = "?" + sym.name
}

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
  type Arc = (Reference, Reference, Node)
  type LanguageTag = Symbol
}

object Vocabulary {
  final val nsuri = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
  final val `type` = nsuri + "type"
  final val nil = nsuri + "nil"
  final val first = nsuri + "first"
  final val rest = nsuri + "rest"
  final val XMLLiteral = nsuri + "XMLLiteral"

  // TODO: split xsd out of rdf vocab?
  final val xsd = "http://www.w3.org/2001/XMLSchema#"
  final val integer = xsd + "integer"
  final val double = xsd + "double"
  final val decimal = xsd + "decimal"
  final val boolean = xsd + "boolean"
}

/**
 * Implement RDF Nodes
 */
trait RDFNodeBuilder extends RDFGraphParts {
  val rdf_type: Label = uri(Vocabulary.`type`)
  val rdf_nil: Label = uri(Vocabulary.nil)
  val rdf_first: Label = uri(Vocabulary.first)
  val rdf_rest: Label = uri(Vocabulary.rest)

  def uri(i: String): Label = Label(i)
  def plain(s: String, lang: Option[LanguageTag]): PlainLiteral = PlainLiteral(s, lang)
  def typed(s: String, dt: String): Literal = TypedLiteral(s, Label(dt))
  def xmllit(e: scala.xml.NodeSeq): Literal = XmlLiteral(e)

  def fresh(hint: String)(implicit s: Scope): Variable = s.fresh(hint)
  def byName(name: String)(implicit s: Scope): Variable = s.byName(name)
}

class Scope(val vars: Iterable[Variable]) {
  def this() = this(List())

  val namespaces = new mutable.Stack[NamespaceBinding]

  import scala.collection.mutable
  val varstack = new mutable.Stack[Variable]
  vars foreach {
    case v @ Variable(n, x) if safeName(n) == n => varstack.push(v)
    case v @ Variable(n, x) => varstack.push(fresh(n))
    case _ => varstack.push(fresh("v"))
  }

  /* baseName is a name that does *not* follow the xyx.123 pattern */
  protected def safeName(name: String): String = {
    val lastChar = name.substring(name.length - 1)
    if ("0123456789".contains(lastChar) &&
      name.contains('.')) name + "_"
    else name
  }

  /**
   * Return a variable for this name, creating one if necessary.
   * @return the same variable given the same name.
   */
  def byName(name: String): Variable = {
    var safe = safeName(name)
    varstack.find { v => v.quote().name == safe } match {
      case None => fresh(safe)
      case Some(v) => v
    }
  }

  /**
   * @param suggestedName: an variable name
   * @return an variable name unique to this scope
   */
  def fresh(suggestedName: String): Variable = {
    assert(suggestedName.nonEmpty)
    val baseName = safeName(suggestedName)
    val b = {
      val seen = varstack.exists { v => v.quote().name == baseName }
      if (seen) Variable(baseName, Some(varstack.size))
      else Variable(baseName, None)
    }

    varstack.push(b)
    b
  }
}