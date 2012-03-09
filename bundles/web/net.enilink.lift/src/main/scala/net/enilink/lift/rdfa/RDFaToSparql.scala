package net.enilink.lift.rdfa

import scala.collection.mutable.HashSet
import scala.collection.mutable.LinkedHashSet
import scala.collection.mutable.StringBuilder
import scala.collection.mutable
import scala.xml.NodeSeq
import scala.xml.UnprefixedAttribute
import net.enilink.komma.core.IBindings
import net.enilink.komma.core.LinkedHashBindings
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

class RDFaToSparql()(implicit s: Scope = new Scope()) extends RDFaParser {
  import scala.collection.mutable
  class ThisScope(val thisNode: Reference = Variable("this", None), val elem: xml.Elem = null)
  val thisStack = new mutable.Stack[ThisScope].push(new ThisScope)

  val sparql: StringBuilder = new StringBuilder()
  val selectVars = new LinkedHashSet[Variable]
  val orderBy = new LinkedHashSet[String]
  val bindings: IBindings[_] = new LinkedHashBindings
  val seen: mutable.Set[Arc] = new mutable.HashSet[Arc]

  var indentation = 0

  def indent {
    indentation = indentation + 1
  }

  def dedent() {
    indentation = indentation - 1
  }

  def addLine(s: String, pos: Int = sparql.length) = {
    val line = new StringBuilder(indentation + s.length() + 1)
    for (i <- 0 to indentation) line.append("\t")
    line.append(s).append('\n')

    sparql.insert(pos, line)
  }

  def toSparql(e: xml.Elem, base: String): (xml.Elem, String) = {
    val (e1, _) = walk(e, base, uri(base), undef, Nil, Nil, null)

    val result = new StringBuilder
    (e1, {
      selectVars.map(toString _).addString(result, "select distinct ", " ", " where {\n")
      result.append(sparql)
      result.append("}\n")
      modifiers(e, result)
      result.toString
    })
  }

  def modifiers(e: xml.Elem, sb: StringBuilder) {
    if (!orderBy.isEmpty) {
      orderBy.addString(sb, "order by ", " ", "\n")
    }

    val limit = (e \ "@data-limit").text
    if (!limit.isEmpty) sb.append("limit ").append(limit).append('\n')

    val offset = (e \ "@data-offset").text
    if (!offset.isEmpty) sb.append("offset ").append(offset).append('\n')
  }

  def hasCssClass(e: xml.Elem, pattern: String) = {
    ("(?:^|\\s)" + pattern).r.findFirstIn((e \ "@class").text) != None
  }

  override def walk(e: xml.Elem, base: String, subj1: Reference, obj1: Reference,
    pending1f: Iterable[Reference], pending1r: Iterable[Reference],
    lang1: Symbol): (xml.Elem, Stream[Arc]) = {

    val isOptional = hasCssClass(e, "optional")
    if (isOptional) {
      addLine("optional {")
      indent
    }

    val result = super.walk(e, base, subj1, obj1, pending1f, pending1r, lang1)

    val filter = (e \ "@data-filter").text
    if (!filter.isEmpty) addLine("filter (" + filter + ")")

    if (isOptional) {
      dedent
      addLine("}")
    }

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

  override def transformResource(e: xml.Elem, subjProp: String, subj: Reference, objProp: String, obj: Reference): xml.Elem = {
    val newE = super.transformResource(e, subjProp, subj, objProp, obj)

    // add order by modifier
    val orderTarget = if (obj != undef && obj.isInstanceOf[Variable]) obj else if (subj != undef && subj.isInstanceOf[Variable]) subj else null
    if (orderTarget != null) {
      lazy val orderAsc = hasCssClass(newE, "asc")
      lazy val orderDesc = hasCssClass(newE, "desc")
      (if (orderAsc) toString(orderTarget) else if (orderDesc) "desc(" + toString(orderTarget) + ")" else null) match {
        case modifier: Any => orderBy.add(modifier)
        case _ =>
      }
    }

    if (obj != undef) {
      thisStack.push(new ThisScope(obj, e))
    }
    newE
  }

  override def transformLiteral(e: xml.Elem, content: NodeSeq, literal: Literal): (xml.Elem, Node) = {
    var (e1, literal1) = super.transformLiteral(e, content, literal)
    if (content.isEmpty && (literal1 match {
      case PlainLiteral("", _) => true
      case XmlLiteral(xml) => "(?:^|\\s)l(?:ift)?:".r.findFirstIn((xml \\ "@class").text) != None
      case _ => false
    })) {
      literal1 = select(fresh("l"))
      e1 = e1.copy(attributes = e1.attributes.append(new UnprefixedAttribute("data-clear-content", "?" + literal1, e1.attributes)))
    } else {
      // content="?someVar"
      literal1 match {
        case PlainLiteral(variable(l), _) => literal1 = createVariable(l).get
        case _ =>
      }
    }
    (e1, literal1)
  }

  override def createVariable(name: String): Option[Reference] = {
    name match {
      case "this" => if (thisStack.isEmpty) None else Some(thisStack.top.thisNode match {
        case v: Variable => select(v)
        case other => other
      })
      case _ => Some(select(new Variable(name, None)))
    }
  }

  def select[V <: Variable](v: V): V = {
    selectVars.add(v)
    v
  }
}