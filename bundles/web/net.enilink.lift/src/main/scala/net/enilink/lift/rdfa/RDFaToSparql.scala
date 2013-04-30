package net.enilink.lift.rdfa

import net.enilink.komma.core.LinkedHashBindings
import net.enilink.komma.core.IBindings
import scala.collection.mutable.HashSet
import scala.collection.mutable.LinkedHashSet
import scala.collection.mutable.StringBuilder
import scala.collection.mutable
import scala.xml.NodeSeq
import scala.xml.UnprefixedAttribute
import net.enilink.lift.rdf.Label
import net.enilink.lift.rdf.Literal
import net.enilink.lift.rdf.Node
import net.enilink.lift.rdf.PlainLiteral
import net.enilink.lift.rdf.Reference
import net.enilink.lift.rdf.Scope
import net.enilink.lift.rdf.TypedLiteral
import net.enilink.lift.rdf.Variable
import net.enilink.lift.rdf.Vocabulary
import net.enilink.lift.rdf.XmlLiteral
import net.enilink.lift.rdf.PlainLiteral
import net.liftweb.util.Helpers._
import scala.xml.NamespaceBinding

/**
 * Can be used to replace relative CURIEs with absolute URIs
 */
trait CURIEExpander extends CURIE {
  override def setExpandedReference(e: xml.Elem, attr: String, ref: Reference) = {
    e % (attr.substring(1) -> ref)
  }

  override def setExpandedReferences(e: xml.Elem, attr: String, refs: Iterable[Reference]) = {
    e % (attr.substring(1) -> refs.mkString(" "))
  }
}

object SparqlFromRDFa {
  def apply(e: xml.Elem, base: String): SparqlFromRDFa = return new RDFaToSparqlParser(e, base) with CURIEExpander
}

trait SparqlFromRDFa {
  def getQueryVariables: Set[Variable]
  def getQuery: String
  def getQuery(bindingName: String, offset: Any, limit: Any): String
  def getElement: xml.Elem

  def getPaginatedQuery(bindingName: String, offset: Any, limit: Any): String
  def getCountQuery(bindingName: String): String
}

private class RDFaToSparqlParser(e: xml.Elem, base: String)(implicit s: Scope = new Scope()) extends RDFaParser with SparqlFromRDFa {
  import scala.collection.mutable
  class ThisScope(val thisNode: Reference = Variable("this", None), val elem: xml.Elem = null)
  val thisStack = new mutable.Stack[ThisScope].push(new ThisScope)

  val sparql: StringBuilder = new StringBuilder
  val selectVars = new LinkedHashSet[Variable]
  val orderBy = new LinkedHashSet[String]
  val bindings: IBindings[_] = new LinkedHashBindings
  val seen: mutable.Set[Arc] = new mutable.HashSet[Arc]

  var indentation = 0
  def indent { indentation = indentation + 1 }
  def dedent { indentation = indentation - 1 }

  var resultElem: xml.Elem = _
  var resultQuery: String = _

  {
    val (e1, _) = walk(e, base, uri(base), undef, Nil, Nil, null)
    val result = new StringBuilder
    addPrefixDecls(result, e1.scope)
    selectVars.map(toString).addString(result, "select distinct ", " ", " where {\n")
    result.append(sparql)
    result.append("}\n")
    modifiers(e, result)
    resultQuery = result.toString
    resultElem = e1
  }

  def addPrefixDecls(query: StringBuilder, scope: NamespaceBinding, seen: Set[String] = Set.empty) {
    scope match {
      case xml.TopScope | null => // do nothing
      case NamespaceBinding(prefix, uri, parent) =>
        if (!seen.contains(prefix)) query.append("prefix ").append(prefix).append(": <").append(uri).append(">\n")
        addPrefixDecls(query, parent, seen + prefix)
    }
  }

  def addLine(s: String, pos: Int = sparql.length) = {
    val line = new StringBuilder(indentation + s.length() + 1)
    for (i <- 0 to indentation) line.append("\t")
    line.append(s).append('\n')
    sparql.insertAll(pos, line)
  }

  def getQuery = resultQuery
  def getQueryVariables = selectVars.toSet
  def getElement = resultElem

  def getQuery(bindingName: String, offset: Any, limit: Any) = {
    val result = new StringBuilder
    result.append("select distinct ?").append(bindingName).append(" where {\n")
    result.append(sparql)
    result.append("}\n")
    modifiers(e, result, false)
    result.append("offset ").append(offset).append("\n")
    result.append("limit ").append(limit).append("\n")
    result.toString
  }

  def getPaginatedQuery(bindingName: String, offset: Any, limit: Any) = {
    val result = new StringBuilder
    addPrefixDecls(result, resultElem.scope)
    selectVars.map(toString).addString(result, "select distinct ", " ", " where {\n")

    // subquery to limit the solutions for given binding name
    result.append("{ ").append(getQuery(bindingName, offset, limit)).append("}\n")
    // end of subquery

    result.append(sparql)
    result.append("}\n")
    modifiers(e, result)
    result.toString
  }

  def getCountQuery(bindingName: String) = {
    val result = new StringBuilder
    result.append("select (count(distinct ?").append(bindingName).append(") as ?count) where {\n")
    result.append(sparql)
    result.append("}\n").toString
  }

  def modifiers(e: xml.Elem, sb: StringBuilder, includeLimitOffset: Boolean = true) {
    if (!orderBy.isEmpty) {
      orderBy.addString(sb, "order by ", " ", "\n")
    }

    if (includeLimitOffset) {
      nonempty(e, "data-offset") foreach { sb.append("offset ").append(_).append('\n') }
      nonempty(e, "data-limit") foreach { sb.append("limit ").append(_).append('\n') }
    }
  }

  /**
   * Returns a value if the attribute with the given name is present and its trimmed text is not empty.
   */
  def nonempty(e: xml.Elem, name: String) = e.attribute(name) map (_.text.trim) filter (_.nonEmpty)

  def hasCssClass(e: xml.Elem, pattern: String) = nonempty(e, "class") exists { ("(?:^|\\s)" + pattern).r.findFirstIn(_).isDefined }

  val EndsWithBraceOrDot = ".*[}.]\\s+".r

  override def walk(e: xml.Elem, base: String, subj1: Reference, obj1: Reference,
    pending1f: Iterable[Reference], pending1r: Iterable[Reference],
    lang1: Symbol): (xml.Elem, Stream[Arc]) = {

    var close = 0
    def addBlock(block: String) { addLine(block + " {"); indent; close += 1 }

    if (hasCssClass(e, "optional")) addBlock("optional")
    if (hasCssClass(e, "exists")) addBlock("filter exists")
    if (hasCssClass(e, "not-exists")) addBlock("filter not exists")

    nonempty(e, "data-pattern") foreach { p =>
      p match {
        case EndsWithBraceOrDot => addLine(p)
        case _ => addLine(p + " . ")
      }
    }
    nonempty(e, "data-bind") foreach { bind => addLine("bind (" + bind + ")") }

    val result = super.walk(e, base, subj1, obj1, pending1f, pending1r, lang1)

    nonempty(e, "data-filter") foreach { filter => addLine("filter (" + filter + ")") }

    while (close > 0) { dedent; addLine("}"); close -= 1 }

    if (thisStack.top.elem eq e) thisStack.pop
    result
  }

  override def walkChildren(parent: xml.Elem, f: (xml.Node) => Seq[Arc]): Seq[Arc] = {
    val isUnion = hasCssClass(parent, "union")
    var prependUnion = false
    parent.child.flatMap { c =>
      val start = sparql.length
      val result = f(c)

      if (isUnion && sparql.length > start) {
        addLine(if (prependUnion) "union {" else "{", start)
        addLine("}")
        prependUnion = true
      }

      result
    }
  }

  override def handleArcs(e: xml.Elem, arcs: Stream[Arc]) = {
    for ((s, p, o) <- arcs.filter(seen.add(_))) {
      addLine(toString(s) + " " + toString(p) + " " + toString(o) + " . ")
    }
    arcs
  }

  def toString(n: Node): String = {
    n match {
      case PlainLiteral(s, lang) => "\"" + s + "\"" + (lang match { case Some(l) => "@" + l case None => "" })
      case TypedLiteral(lex, dt) => "\"" + lex + "\"^^" + toString(dt)
      case XmlLiteral(content) => "\"" + content + "\"^^" + toString(Label(Vocabulary.XMLLiteral))
      case Label(uri) => "<" + uri + ">"
      case v: Variable => "?" + v
    }
  }

  override def subjectObject(obj1: Reference, e: xml.Elem, base: String,
    norel: Boolean,
    types: Iterable[Reference], props: Iterable[Reference]): (xml.Elem, Reference, Reference, Boolean) = {
    val (e1, subj, obj, skip) = super.subjectObject(obj1, e, base, norel, types, props)

    if (!skip && props.isEmpty) {
      (if (obj != undef && obj.isInstanceOf[Variable]) obj else if (subj != undef && subj.isInstanceOf[Variable]) subj else null) match {
        case v: Variable => addToOrderBy(e1, v)
        case _ =>
      }
    }

    if (obj != undef) {
      thisStack.push(new ThisScope(obj, e1))
    }

    return (e1, subj, obj, skip)
  }

  /** Adds orderBy modifier for the given variable */
  def addToOrderBy(e: xml.Elem, variable: Variable) {
    lazy val orderAsc = hasCssClass(e, "asc")
    lazy val orderDesc = hasCssClass(e, "desc")
    (if (orderAsc) toString(variable) else if (orderDesc) "desc(" + toString(variable) + ")" else null) match {
      case modifier: Any => orderBy.add(modifier)
      case _ =>
    }
  }

  override def transformLiteral(e: xml.Elem, content: NodeSeq, literal: Literal): (xml.Elem, Node) = {
    var (e1, literal1) = super.transformLiteral(e, content, literal)
    if (content.isEmpty && (literal1 match {
      case PlainLiteral("", _) => true
      case XmlLiteral(xml) => "(?:^|\\s)l(?:ift)?:".r.findFirstIn((xml \\ "@class").text) != None
      case _ => false
    })) {
      literal1 = select(fresh("l_"))
      e1 = e1.copy(attributes = e1.attributes.append(new UnprefixedAttribute("data-clear-content", "?" + literal1, e1.attributes)))
    } else {
      // content="?someVar"
      literal1 match {
        case PlainLiteral(variable(l), _) => literal1 = createVariable(l).get
        case _ =>
      }
    }
    literal1 match {
      case v: Variable => addToOrderBy(e1, v)
      case _ =>
    }
    (e1, literal1)
  }

  override def createVariable(name: String): Option[Reference] = {
    name match {
      // TODO is support for ?this variable required?
      //      case "this" => if (thisStack.isEmpty) None else Some(thisStack.top.thisNode match {
      //        case v: Variable => select(v)
      //        case other => other
      //      })
      case _ => Some(select(Variable(name.substring(1), None)))
    }
  }

  def select[V <: Variable](v: V): V = {
    selectVars.add(v)
    v
  }
}

