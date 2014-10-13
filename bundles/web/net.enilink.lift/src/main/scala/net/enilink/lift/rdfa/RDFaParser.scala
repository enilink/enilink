package net.enilink.lift.rdfa

import scala.Array.fallbackCanBuildFrom
import scala.util.matching.Regex
import scala.xml.NodeSeq.seqToNodeSeq
import net.enilink.lift.rdf._
import Util.combine
import scala.collection.mutable.ListBuffer
import scala.xml.NodeSeq
import scala.xml.TopScope
import scala.xml.NamespaceBinding
import net.enilink.komma.core.URIs
import scala.language.postfixOps

object RDFaParser extends RDFaParser()(new Scope())

trait RDFaUtils {
  private val PREFIX_PATTERN = "([^\\s:]+):\\s+([\\S]+)".r

  def findPrefixMappings(prefixValue: String, bindings: NamespaceBinding) = {
    val prefixes = PREFIX_PATTERN.findAllIn(prefixValue).matchData.foldLeft(bindings) {
      (
        (previous, mapping) => new NamespaceBinding(mapping.group(1), mapping.group(2), previous))
    }
    prefixes
  }
}

/**
 * This parser is host-language neutral, so caller must
 * fish base out of HTML head.
 *
 * @See: <a href="http://www.w3.org/TR/rdfa-syntax/">RDFa in XHTML: Syntax and Processing</a>
 * W3C Recommendation 14 October 2008
 *
 */
class RDFaParser()(implicit val s: Scope = new Scope()) extends CURIE with RDFaUtils {
  val undef = fresh("undef") // null seems to cause type mismatch errors

  def getArcs(e: xml.Elem, base: String): Stream[Arc] = {
    walk(e, base, uri(base), undef, Nil, Nil, null) _2
  }

  /**
   * Walk element recursively, finding arcs (atomic formulas).
   *
   * based on section <a href="http://www.w3.org/TR/rdfa-syntax/#sec_5.5.">5.5. Sequence</a>
   *
   *
   * @param subj1: [parent subject] from step 1
   * @param obj1: [parent object] from step 1
   * @param pending1f: propertys of [list of incomplete triples]
   *                   from evaluation context, forward direction
   * @param pending1r: propertys of [list of incomplete triples]
   *                   from evaluation context, reverse direction
   * @param lang1: [language] from step 1
   *
   */
  def walk(e: xml.Elem, base: String,
    subj1: Reference, obj1: Reference,
    pending1f: Iterable[Reference], pending1r: Iterable[Reference],
    lang1: Symbol): (xml.Elem, Stream[Arc]) = {
    assert(subj1 != undef) // with NotNull doesn't seem to work. scalaq?

    // step 2., URI mappings, xmlns is taken care of by scala.xml
    // support for the @prefix attribute
    val namespaces = findPrefixMappings((e \ "@prefix").text, e.scope)
    val hasPrefixMappings = namespaces ne e.scope
    if (hasPrefixMappings) s.namespaces.push(namespaces)
    val eWithPrefixes = if (hasPrefixMappings) e.copy(scope = namespaces) else e

    // step 3. [current language]
    val lang2 = eWithPrefixes \ "@{http://www.w3.org/XML/1998/namespace}lang"
    val lang = {
      if (lang2.isEmpty) lang1
      else if (lang2.text == "") null
      else Symbol(lang2.text.toLowerCase)
    }

    // steps 4 and 5, refactored
    val (e01, relterms) = refN(eWithPrefixes, "@rel", true)
    val (e02, revterms) = refN(e01, "@rev", true)
    val (e03, types) = refN(e02, "@typeof", false)
    val (e04, props) = refN(e03, "@property", false)
    val norel = relterms.isEmpty && revterms.isEmpty
    val (e1, subj45, objref5, skip) = subjectObject(obj1, e04, base, norel, types, props)

    // step 6. typeof
    val arcs6 = {
      val target = if (objref5 != undef) objref5 else subj45
      if (target != undef) types.toStream.map(cls => (target, rdf_type, cls)) else Stream.empty
    }

    // step 7 rel/rev triples
    // HTML grammar guarantees a subject at this point, I think,
    // but in an effort to stay host-language-neutral, let's double-check
    val arcs7 = {
      if (objref5 != undef && subj45 != undef) {
        (for (p <- relterms.toStream) yield (subj45, p, objref5)) ++
          (for (p <- revterms.toStream) yield (objref5, p, subj45))
      } else Stream.empty
    }

    // step 8 incomplete triples.
    val (objref8, pending8f, pending8r) = {
      if (objref5 == undef && !(relterms.isEmpty && revterms.isEmpty))
        (fresh("x8"), relterms, revterms)
      else (objref5, Nil, Nil)
    }

    // step 9 literal object
    val ((e2, arcs9, xmlobj), isLiteral) = {
      if (!props.isEmpty) (literalObject(subj45, props, lang, e1), true)
      else ((e1, Stream.empty, false), false)
    }

    // step 10 complete incomplete triples.
    val arcs10: Stream[Arc] = if (!skip && subj45 != undef) {
      pending1f.toStream.map((subj1, _, subj45)) ++
        pending1r.toStream.map((subj45, _, subj1))
    } else Stream.empty

    // step 11. recur
    var newE = e2
    val arcs = handleArcs(newE, arcs6 ++ arcs7 ++ arcs9 ++ arcs10, isLiteral)
    val childArcs = (if (!xmlobj) {
      val newChild = new ListBuffer[xml.Node]
      var changedChild = false
      // TODO find out why newE.child.toStream.flatMap misses some child elements
      val childArcs = walkChildren(newE, {
        case c: xml.Elem => {
          var (newC, arcs) = if (skip) {
            walk(c, base, subj1, obj1, pending1f, pending1r, lang)
          } else {
            walk(c, base,
              if (subj45 != undef) subj45 else subj1,
              (if (objref8 != undef) objref8
              else if (subj45 != undef) subj45
              else subj1),
              pending8f, pending8r, lang)
          }
          changedChild |= !(newC eq c)
          newChild.append(newC)
          arcs
        }
        /* never mind stuff other than elements */
        case c: xml.Node => {
          newChild.append(c)
          Stream.empty
        }
        case _ => Stream.empty
      })
      if (changedChild) newE = newE.copy(child = newChild)
      childArcs
    } else Stream.empty)

    if (hasPrefixMappings) s.namespaces.pop
    (newE, arcs ++ childArcs)
  }

  def walkChildren(parent: xml.Elem, f: (xml.Node) => Seq[Arc]): Seq[Arc] = {
    parent.child.flatMap(f)
  }

  /**
   * steps 4 and 5, refactored
   * @return: new subject, new object ref, skip flag
   */
  def subjectObject(obj1: Reference, e: xml.Elem, base: String,
    norel: Boolean,
    types: Iterable[Reference], props: Iterable[Reference]): (xml.Elem, Reference, Reference, Boolean) = {
    val (e1, about) = ref1(e, "@about", base)
    lazy val src = e \ "@src"
    lazy val (e2, resource) = ref1(e1, "@resource", base)
    lazy val href = e \ "@href"

    val (subjProp, subj45x) = {
      if (!about.isEmpty) ("@about", about.get)
      else if (!src.isEmpty) ("@src", uri(combine(base, src.text)))
      else if (norel && !resource.isEmpty) ("@resource", resource.get)
      else if (norel && !href.isEmpty) ("@href", uri(combine(base, href.text)))
      // hmm... host language creeping in here...
      else if (e.label == "head" || e.label == "body") (null, uri(combine(base, "")))
      else if (!types.isEmpty && resource.isEmpty && href.isEmpty) ("@about", fresh("x4"))
      else (null, undef)
    }

    val (objProp, objref5) = if (!resource.isEmpty) ("@resource", resource.get)
    else if (!href.isEmpty) ("@href", uri(combine(base, href.text)))
    else (null, undef)

    val subj45 = if (subj45x != undef) subj45x else obj1
    val skip = norel && (subj45x == undef) && props.isEmpty

    return (e2, subj45, objref5, skip)
  }

  def handleArcs(e: xml.Elem, arcs: Stream[Arc], isLiteral : Boolean) = {
    arcs
  }

  def transformLiteral(e: xml.Elem, content: NodeSeq, literal: Literal): (xml.Elem, Node) = {
    (e, literal)
  }

  /**
   * step 9 literal object
   * side effect: pushes statements
   * @return: (arcs, xmllit) where xmllit is true iff object is XMLLiteral
   */
  def literalObject(subj: Reference, props: Iterable[Reference], lang: Symbol,
    e: xml.Elem): (xml.Elem, Stream[Arc], Boolean) = {
    val content = e \ "@content"
    val datatype = e \ "@datatype"

    def sayit(obj: Node) = {
      for (p <- props.toStream) yield (subj, p, obj)
    }

    var (e1, literal1, xmlobj) = createLiteral(e, lang, datatype, content)
    var (e2, literal2) = transformLiteral(e1, content, literal1)
    (e2, (if (literal2 != null) sayit(literal2) else Stream.empty), xmlobj)
  }

  def createLiteral(e: xml.Elem, lang: Symbol, datatype: NodeSeq, content: NodeSeq): (xml.Elem, Literal, Boolean) = {
    lazy val lex = if (!content.isEmpty) content.text else e.text
    def txt(s: String) = if (lang == null) plain(s, None) else plain(s, Some(lang))
    if (datatype.isEmpty || datatype.text.isEmpty) {
      // literals without @datatype are always handled as plain literals
      (e, txt(lex), false)
    } else datatype.text match {
      case parts(p, l) if p != null => {
        try {
          val dt = expand(p, l, e)
          if (dt == Vocabulary.XMLLiteral) (e, xmllit(e.child), true) else (e, typed(lex, dt), false)
        } catch {
          case nde: NotDefinedError => (e, null, false)
        }
      }
      /* TODO: update handling of goofy datatype values based on WG
           * response to 3 Feb comment. */
      case _ => (e, null, false)
    }
  }
}

/**
 * There is perhaps a more general notion of CURIE, but this captures
 * only the RDFa-specific notion.
 */
trait CURIE extends RDFNodeBuilder {
  import scala.util.matching.Regex

  /*
   * spec says
   *   prefix := NName
   * but then speaks of :p having a "default prefix"
   * so we match the empty prefix here.
   */
  final val parts = new Regex("""^(?:([^:]*)?:)?(.*)$""",
    "prefix", "reference")
  final val safeCurie = new Regex("""^\[(.*)\]$""", "curie")
  final val variable = "^([?$].*)$".r

  /**
   * expand one safe curie or URI reference
   */
  def ref1(e: xml.Elem, attr: String, base: String)(implicit s: Scope): (xml.Elem, Option[Reference]) = {
    val attrval = e \ attr
    val (ref, expanded) = if (attrval.isEmpty) (None, false)
    else {
      val curieOrIri = attrval.text match {
        case safeCurie(curie) => curie
        case other => other
      }
      expandCurie(e, curieOrIri)
    }
    (if (expanded && ref.isDefined) setExpandedReference(e, attr, ref.get) else e, ref)
  }

  def setExpandedReference(e: xml.Elem, attr: String, ref: Reference) = e

  // 9.3. @rel/@rev attribute values
  def reserved = Array("alternate",
    "appendix",
    "bookmark",
    "cite",
    "chapter",
    "contents",
    "copyright",
    "first",
    "glossary",
    "help",
    "icon",
    "index",
    "last",
    "license",
    "meta",
    "next",
    "p3pv1",
    "prev",
    "role",
    "section",
    "stylesheet",
    "subsection",
    "start",
    "top",
    "up")
  final val xhv = "http://www.w3.org/1999/xhtml/vocab#"

  def expandCurie(e: xml.Elem, curieOrIri: String)(implicit s: Scope): (Option[Reference], Boolean) = {
    var expanded = false
    val ref: Option[Reference] = curieOrIri match {
      // support for SPARQL variables
      case variable(v) => createVariable(v) match {
        // anonymous variables must be expanded
        case newVar @ Some(_) if v == "?" || v == "$" =>
          expanded = true; newVar
        case other => other
      } // ?foo
      case parts(p, l) if (p == null) => None
      case parts(p, l) if (p == "xml") => None // xml:foo

      // support for blank node variables
      case parts("_", "") => Some(byName("_"))
      case parts("_", l) if l.startsWith("_") => Some(byName(l))
      case parts("_", l) => Some(byName(l))

      case token @ parts(p, l) =>
        val result = if (p != null) {
          // if prefix and local part is given then try to expand the CURIE
          try {
            val exandedRef = Some(uri(expand(p, l, e)))
            expanded = true
            exandedRef
          } catch {
            case e: NotDefinedError => None
          }
        } else None
        result match {
          // this is the case if token is an absolute IRI  
          case None if !token.isEmpty => try {
            // test if token is a valid IRI
            URIs.createURI(token)
            Some(uri(token))
          } catch {
            // token is not a valid IRI
            case _: Throwable => None
          }
          case other => other
        }
      case _ => None
    }
    (ref, expanded)
  }

  def refN(e: xml.Elem, attr: String, bare: Boolean)(implicit s: Scope): (xml.Elem, Iterable[Reference]) = {
    var expanded = false
    val refs = "\\s+".r.split((e \ attr).text) flatMap { token =>
      if (bare && reserved.contains(token.toLowerCase)) Some(uri(xhv + token.toLowerCase))
      else {
        val (ref, refExpanded) = expandCurie(e, token)
        expanded |= refExpanded
        ref
      }
    }
    (if (expanded) setExpandedReferences(e, attr, refs) else e, refs)
  }

  def setExpandedReferences(e: xml.Elem, attr: String, refs: Iterable[Reference]) = e

  def createVariable(name: String): Option[Reference] = None

  def expand(p: String, l: String, e: xml.Elem)(implicit s: Scope): String = {
    val ns = if (p == "") xhv else {
      var ns: String = null
      // use local namespace bindings (extracted from @prefix) to lookup prefix 
      if (!s.namespaces.isEmpty) ns = s.namespaces.top.getURI(p)
      // use default XML namespaces
      if (ns == null) ns = e.getNamespace(p)
      ns
    }

    if (ns == null) {
      // TODO: find out if we're supposed to ignore this error.
      throw new NotDefinedError("no such prefix " + p + " on element " + e)
    }
    ns + l
  }
}

class NotDefinedError(msg: String) extends Error("not defined: " + msg)