package net.enilink.platform.lift.rdfa

import net.enilink.komma.core.{IBindings, LinkedHashBindings}
import net.enilink.platform.lift.rdf._

import scala.collection.mutable.{LinkedHashSet, StringBuilder}
import scala.xml.{Elem, NamespaceBinding, NodeSeq, Null, UnprefixedAttribute}

/**
 * Can be used to replace relative CURIEs with absolute URIs
 */
trait CURIEExpander extends CURIE {
  override def setExpandedReference(e: xml.Elem, attr: String, ref: Reference): Elem = {
    e.copy(attributes = e.attributes.append(new UnprefixedAttribute(attr.substring(1), ref.toString, Null)))
  }

  override def setExpandedReferences(e: xml.Elem, attr: String, refs: Iterable[Reference]): Elem = {
    e.copy(attributes = e.attributes.append(new UnprefixedAttribute(attr.substring(1), refs.mkString(" "), Null)))
  }
}

object SparqlFromRDFa {
  def apply(e: xml.Elem, base: String, varResolver: Option[VariableResolver] = None): SparqlFromRDFa = new RDFaToSparqlParser(e, base, varResolver) with CURIEExpander
}

trait VariableResolver {
  def resolve(name: String): Option[Reference]
}

trait SparqlFromRDFa {
  def getQueryVariables: Set[Variable]
  def getQuery: String
  def getQuery(bindingName: String, offset: Any, limit: Any, isSubQuery : Boolean = false): String
  def getElement: xml.Elem

  def getPaginatedQuery(bindingName: String, offset: Any, limit: Any): String
  def getCountQuery(bindingName: String): String
}

class SubSelectRDFaToSparqlParser(
    e: xml.Elem,
    base: String,
    subj1: Reference,
    obj1: Reference,
    pending1f: Iterable[Reference],
    pending1r: Iterable[Reference],
    lang1: Symbol,
    varResolver: Option[VariableResolver],
    val explicitProjection: Option[String],
    override val initialIndentation: Int,
    override val initialStrictness: Boolean)(implicit s: Scope = new Scope()) extends RDFaToSparqlParser(e, base, varResolver) {

  override def walkRootElement : (xml.Elem, LazyList[Arc]) = {
    walk(e, base, subj1, obj1, pending1f, pending1r, lang1)
  }

  override def projection: String = {
    explicitProjection.map(_.toString) getOrElse super.projection
  }

  override def addPrefixDecls(query: StringBuilder, scope: NamespaceBinding, seen: Set[String]) : Unit = {
    // do not add any prefixes
  }
}

class RDFaToSparqlParser(e: xml.Elem, base: String, varResolver: Option[VariableResolver] = None)(implicit s: Scope = new Scope()) extends RDFaParser with SparqlFromRDFa {
  import RDFaHelpers._

  import scala.collection.mutable
  class ThisScope(val thisNode: Reference = Variable("this", None), val elem: xml.Elem = null)
  val thisStack: mutable.Stack[ThisScope] = new mutable.Stack[ThisScope].push(new ThisScope)

  val sparql: StringBuilder = new StringBuilder
  val selectVars = new LinkedHashSet[Variable]
  val orderBy = new LinkedHashSet[String]
  val bindings: IBindings[_] = new LinkedHashBindings
  val seen: mutable.Set[Arc] = new mutable.HashSet[Arc]

  val initialIndentation = 0
  val initialStrictness = false

  var indentation: Int = initialIndentation
  def indent : Unit = { indentation = indentation + 1 }
  def dedent : Unit = { indentation = indentation - 1 }

  var strict: Boolean = initialStrictness
  var withinFilter = 0

  var resultElem: xml.Elem = _
  var resultQuery: String = _

  def projection: String = {
    selectVars.map(toString).mkString(" ")
  }

  {
    val (e1, _) = walkRootElement
    val result = new StringBuilder
    addPrefixDecls(result, e1.scope)
    result.append("select distinct " + projection + " where {\n")
    result.append(patterns)
    result.append("}\n")
    modifiers(e, result)
    resultQuery = result.toString
    resultElem = e1
  }

  def walkRootElement : (xml.Elem, LazyList[Arc]) = {
    walk(e, base, uri(base), undef, Nil, Nil, null)
  }

  def addPrefixDecls(query: StringBuilder, scope: NamespaceBinding, seen: Set[String] = Set.empty) : Unit = {
    scope match {
      case xml.TopScope | null => // do nothing
      case NamespaceBinding(prefix, uri, parent) =>
        if (!seen.contains(prefix)) query.append("prefix ").append(prefix).append(": <").append(uri).append(">\n")
        addPrefixDecls(query, parent, seen + prefix)
    }
  }

  def addLine(s: String, pos: Int = sparql.length): sparql.type = {
    val line = new StringBuilder(indentation + s.length() + 1)
    for (i <- 0 to indentation) line.append("\t")
    line.append(s).append('\n')
    sparql.insertAll(pos, line)
  }

  def getQuery: String = resultQuery
  def getQueryVariables: Set[Variable] = selectVars.toSet
  def getElement: Elem = resultElem

  private def patterns = if (sparql.length == 0) {
    // replace empty graph pattern {} with bind statements
    val result = new StringBuilder
    selectVars.map(toString).foreach { v => result.append("\tbind (" + v + " as " + v + ")\n") }
    result.toString
  } else {
    sparql
  }

  def getQuery(bindingName: String, offset: Any, limit: Any, isSubQuery : Boolean = false): String = {
    val result = new StringBuilder
    if (!isSubQuery) addPrefixDecls(result, resultElem.scope)
    result.append("select distinct ?").append(bindingName).append(" where {\n")
    result.append(patterns)
    result.append("}\n")
    modifiers(e, result, false)
    result.append("offset ").append(offset).append("\n")
    result.append("limit ").append(limit).append("\n")
    result.toString
  }

  def getPaginatedQuery(bindingName: String, offset: Any, limit: Any): String = {
    val result = new StringBuilder
    addPrefixDecls(result, resultElem.scope)
    selectVars.map(toString).addString(result, "select distinct ", " ", " where {\n")

    // subquery to limit the solutions for given binding name
    result.append("{ ").append(getQuery(bindingName, offset, limit, true)).append("}\n")
    // end of subquery

    // use sparql instead of patterns here since
    // bindings are already generated by sub-query
    result.append(sparql)
    result.append("}\n")
    modifiers(e, result)
    result.toString
  }

  def getCountQuery(bindingName: String): String = {
    val result = new StringBuilder
    addPrefixDecls(result, resultElem.scope)
    result.append("select (count(distinct ?").append(bindingName).append(") as ?count) where {\n")
    result.append(patterns)
    result.append("}\n").toString
  }

  def modifiers(e: xml.Elem, sb: StringBuilder, includeLimitOffset: Boolean = true) : Unit = {
    if (!orderBy.isEmpty) {
      orderBy.addString(sb, "order by ", " ", "\n")
    }

    if (includeLimitOffset) {
      nonempty(e, "data-offset") foreach { sb.append("offset ").append(_).append('\n') }
      nonempty(e, "data-limit") foreach { sb.append("limit ").append(_).append('\n') }
    }
  }

  def doMaybeStrict(e: xml.Elem, block: => (xml.Elem, LazyList[Arc])): (Elem, LazyList[(Reference, Reference, Node)]) = {
    val current = e.attribute("data-strict").map(_.toString)
      .collect {
        case m if "false" == m.toLowerCase => false;
        case _ => true
      } getOrElse strict

    val old = strict
    strict = current
    val result = block
    strict = old
    result
  }

  override def walk(e: xml.Elem, base: String, subj1: Reference, obj1: Reference,
    pending1f: Iterable[Reference], pending1r: Iterable[Reference],
    lang1: Symbol): (xml.Elem, LazyList[Arc]) = {
    if (nonempty(e, "data-lift") exists { _ == "head" }) {
      // ignore elements that are later pulled up into <head>
      // this usually includes <script>, <link> etc.
      (e, LazyList.empty)
    } else if (e.attribute("data-select").isDefined) {
      // remove data-select to prevent endless recursion
      val e1 = e.copy(attributes = e.attributes.remove("data-select"))
      // create sub select
      indent
      val innerQuery = new SubSelectRDFaToSparqlParser(
        e1, base, subj1, obj1, pending1f, pending1r, lang1, varResolver, nonempty(e, "data-select"), indentation, strict
      ).getQuery
      sparql.append("{\n" + innerQuery + "}\n")
      dedent
      (e1, LazyList.empty)
    } else {
      doMaybeStrict(e, {
        var close = 0
        var closeFilter = 0
        def addBlock(block: String) : Unit = { addLine(block + "{"); indent; close += 1 }
        def addFilter(block: String) : Unit = { addBlock(block); withinFilter += 1; closeFilter += 1 }
        if (hasCssClass(e, "group")) addBlock("")
        if (hasCssClass(e, "optional")) addBlock("optional ")
        if (hasCssClass(e, "exists")) addFilter("filter exists ")
        if (hasCssClass(e, "not-exists")) addFilter("filter not exists ")

        nonempty(e, "data-pattern") foreach { p =>
          var pTrimmed = p.trim
          // allow references to current subject node
          pTrimmed = pTrimmed.replace("?_", subj1.toString)
          if (pTrimmed.endsWith(".") || pTrimmed.endsWith("}")) addLine(p) else addLine(p + " . ")
        }
        nonempty(e, "data-bind") foreach { bind => addLine("bind (" + bind + ")") }
        val result = super.walk(e, base, subj1, obj1, pending1f, pending1r, lang1)
        nonempty(e, "data-filter") foreach { filter => addLine("filter (" + filter + ")") }
        while (close > 0) { dedent; addLine("}"); close -= 1 }
        withinFilter -= closeFilter
        if (thisStack.top.elem eq e) thisStack.pop
        result
      })
    }
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

  override def handleArcs(e: xml.Elem, arcs: LazyList[Arc], isLiteral: Boolean): LazyList[(Reference, Reference, Node)] = {
    for ((s, p, o) <- arcs.filter(seen.add(_))) {
      addLine(toString(s) + " " + toString(p) + " " + toString(o) + " . ")
      if (strict) {
        if (isLiteral) {
          addLine("FILTER ( isLiteral(" + toString(o) + ") ) ")
        } else {
          addLine("FILTER ( !isLiteral(" + toString(o) + ") ) ")
        }
      }
    }
    arcs
  }

  def toString(n: Node): String = {
    n match {
      case PlainLiteral(s, lang) => "\"" + s + "\"" + (lang match { case Some(l) => "@" + l.name case None => "" })
      case TypedLiteral(lex, dt) => "\"" + lex + "\"^^" + toString(dt)
      case XmlLiteral(content) => "\"" + content + "\"^^" + toString(Label(Vocabulary.XMLLiteral))
      case Label(uri) => "<" + uri + ">"
      case v: Variable => v.toString
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

    (e1, subj, obj, skip)
  }

  /** Adds orderBy modifier for the given variable */
  def addToOrderBy(e: xml.Elem, variable: Variable) : Unit = {
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
      e1 = e1.copy(attributes = e1.attributes.append(new UnprefixedAttribute("data-clear-content", literal1.toString, e1.attributes)))
    } else {
      // content="?someVar"
      literal1 match {
        case PlainLiteral(variable(l), _) => {
          literal1 = createVariable(l).get
          l match {
            // replace anonymous variable
            case "?" | "$" => e1 = e1.copy(attributes = e1.attributes.append(new UnprefixedAttribute("content", literal1.toString, Null)))
            case _ =>
          }
        }
        case _ =>
      }
    }
    literal1 match {
      case v: Variable => addToOrderBy(e1, v)
      case _ =>
    }
    (e1, literal1)
  }

  override def createVariable(name: String): Option[Reference] = varResolver.map(_.resolve(name)) getOrElse (name match {
    case "?" | "$" => Some(select(fresh("v")))
    case _ => Some(select(Variable(name.substring(1), None)))
  })

  def select[V <: Variable](v: V): V = {
    // only select variable if we are not in a "filter exists" or "filter not exists" block
    if (withinFilter == 0) selectVars.add(v)
    v
  }
}

