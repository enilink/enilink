package net.enilink.lift.rdfa.template

import scala.collection.mutable
import scala.xml.Elem
import scala.xml.MetaData
import scala.xml.NodeSeq
import scala.xml.UnprefixedAttribute
import net.enilink.komma.core.IBindings
import net.enilink.komma.core.IReference
import net.enilink.lift.snippet.CurrentContext
import net.enilink.lift.snippet.RdfContext
import net.liftweb.common.Full
import net.liftweb.http.PageName
import net.liftweb.http.S
import net.liftweb.util.Helpers.pairToUnprefixed
import net.liftweb.util.Helpers.strToSuperArrowAssoc
import net.enilink.lift.rdfa.RDFaUtils
import net.enilink.komma.core.ILiteral
import scala.xml.Null
import net.enilink.lift.snippet.RdfContext
import net.enilink.komma.core.URI
import net.enilink.komma.core.IEntity
import net.enilink.komma.core.URIImpl

trait RDFaTemplates extends RDFaUtils {
  /**
   * Process lift templates while changing the RDFa context resources
   */
  def processSurroundAndInclude(ns: NodeSeq): NodeSeq = {
    def processNode(n: xml.Node): NodeSeq = {
      val value = CurrentContext.value
      n match {
        case e: ElemWithRdfa => {
          val result = e.copy(child = processSurroundAndInclude(e.child))
          CurrentContext.withValue(Full(e.context)) {
            S.session.get.processSurroundAndInclude(PageName.get, result)
          }
        }
        case e: Elem => e.copy(child = processSurroundAndInclude(e.child))
        case other => other
      }
    }

    ns.flatMap(processNode _)
  }

  def withPrefixes(prefixes: Seq[String], values: Set[String]) = {
    values ++ values.flatMap(v => prefixes.map(_ + v))
  }

  private val rdfaAttributes = withPrefixes(List("data-", "data-clear-"), Set("about", "src", "rel", "rev", "property", "href", "resource", "content")) ++ List("data-if", "data-unless")
  private val Variable = "^[?$]([^=]+)$".r

  // isTemplate is required for disambiguation because xml.Node extends Seq[xml.Node]
  class Key(val ctxs: Seq[RdfContext], val nodeOrNodeSeq: AnyRef, val isTemplate: Boolean = false) {
    val hashCodeVal = 41 * System.identityHashCode(nodeOrNodeSeq) + ctxs.hashCode + (if (isTemplate) 1 else 0)

    override def hashCode = hashCodeVal

    override def equals(other: Any) = {
      other match {
        case k: Key => (nodeOrNodeSeq eq k.nodeOrNodeSeq) && isTemplate == k.isTemplate && ctxs == k.ctxs
        case _ => false
      }
    }
  }

  class ReplacementMap extends mutable.HashMap[xml.Node, xml.Node] {
    override def elemHashCode(e: xml.Node) = System.identityHashCode(e)
    override def elemEquals(a: xml.Node, b: xml.Node) = a eq b
  }

  /** Marks insertion points of new nodes if previous template transformations returned an empty result. */
  class InsertionMarker extends Elem(null, "insertionMarker", new UnprefixedAttribute("class", "clearable", scala.xml.Null), xml.TopScope)
  /** Marks nodes which should always be skipped once this marker was added. */
  class SkipMarker extends Elem(null, "skipMarker", new UnprefixedAttribute("class", "clearable", scala.xml.Null), xml.TopScope)

  final val attribute = "^(?:data(?:-clear)?-)?(.*)".r

  def transform(ctx: RdfContext, template: Seq[xml.Node])(bindings: IBindings[_], inferred: Boolean, existing: mutable.Map[Key, Seq[xml.Node]]): Seq[xml.Node] = {
    def shorten(uri: URI, currentCtx: RdfContext, elem: xml.Elem) = {
      val namespace = uri.namespace.toString
      lazy val uriStr = uri.toString
      var prefix = currentCtx.prefix.getPrefix(namespace)
      if (prefix == null) prefix = elem.scope.getPrefix(namespace)
      if (prefix == null) uri.toString else prefix + ":" + uriStr.substring(scala.math.min(namespace.length, uriStr.length))
    }
    def internalTransform(ctxs: Seq[RdfContext], template: Seq[xml.Node]): Seq[xml.Node] = {
      var replacedNodes: mutable.Map[xml.Node, xml.Node] = null
      val ctx = ctxs.last
      template.flatMap(tNode => {
        if (replacedNodes != null) replacedNodes.clear
        var currentCtx = ctx
        def changeContext(attributeName: String, rdfValue: Any) {
          // check if context needs to be changed for children
          attributeName match {
            case "data-if" => // does not change context
            case attribute("rel" | "rev" | "property") =>
              currentCtx = currentCtx.copy(predicate = rdfValue)
            case _ =>
              currentCtx = currentCtx.copy(subject = rdfValue)
          }
        }

        val newNodesForTemplate = tNode match {
          case tElem: Elem => {
            val (result, skipNode) = if (tElem.attributes.isEmpty) (tElem, false) else {
              var removeNode = false
              var attributes = tElem.attributes
              val prefixes = findPrefixes(tElem, tElem.scope)
              if (prefixes ne tElem.scope) currentCtx = currentCtx.copy(prefix = prefixes)
              tElem.attributes.foreach(meta =>
                if (attributes != null && !meta.isPrefixed && rdfaAttributes.contains(meta.key)) {
                  def shortRef(ref: IReference) = {
                    val uri = ref.getURI
                    if (uri == null) ref
                    else if (uri.localPart.isEmpty || (meta.key match { case attribute("href" | "src") => true case _ => false })) uri
                    else shorten(uri, currentCtx, tElem)
                  }

                  meta.value.text match {
                    // remove nodes of type <span data-if="inferred">This data is inferred or explicit.</span>
                    // if we are currently processing explicit bindings
                    case "inferred" if meta.key == "data-if" => {
                      if (!inferred) {
                        attributes = null
                        removeNode = true
                      } else attributes = attributes.remove(meta.key)
                    }
                    // fill variables for nodes of type <span about="?someVar">Data about some subject.</span>
                    case Variable(v) => {
                      // TODO is support for ?this variable required?
                      // val rdfValue = if (v == "this") ctx.subject else bindings.get(v)
                      val rdfValue = bindings.get(v)
                      if (rdfValue != null) changeContext(meta.key, rdfValue)
                      val attValue = rdfValue match {
                        case ref: IReference => shortRef(ref)
                        case literal: ILiteral => {
                          // add datatype and lang attributes
                          if (literal.getDatatype != null) {
                            attributes = attributes.append(new UnprefixedAttribute("datatype", shorten(literal.getDatatype, currentCtx, tElem), Null))
                          } else if (literal.getLanguage != null) {
                            attributes = attributes.append(new UnprefixedAttribute("lang", literal.getLanguage, Null))
                          }
                          literal.getLabel
                        }
                        case other => other
                      }

                      if (attValue == null) { if (meta.key == "data-unless") attributes = attributes.remove(meta.key) else attributes = null }
                      else if (meta.key.startsWith("data-clear-") || meta.key == "data-if") attributes = attributes.remove(meta.key)
                      else if (meta.key == "data-unless") { attributes = null; removeNode = true }
                      else attributes = attributes.append(new UnprefixedAttribute(meta.key, attValue.toString, meta.next))
                    }
                    case iriStr if !iriStr.isEmpty =>
                      try {
                        val Iri = URIImpl.createURI(iriStr)
                        if (!Iri.isRelative()) {
                          // do also switch contexts for given constant CURIEs
                          val rdfValue = ctx.subject match {
                            case e: IEntity => e.getEntityManager.find(Iri)
                            case _ => null
                          }
                          if (rdfValue != null) {
                            changeContext(meta.key, rdfValue)
                            attributes = attributes.append(new UnprefixedAttribute(meta.key, String.valueOf(shortRef(rdfValue)), meta.next))
                          }
                        }
                      } catch {
                        case iae: IllegalArgumentException => // ignore, iriStr is an invalid IRI
                      }
                    case _ =>
                  }
                })
              if (attributes == null) (null, removeNode) else (tElem.copy(attributes = attributes), false)
            }

            val currentCtxs = if (currentCtx == ctx) ctxs else ctxs ++ List(currentCtx)

            var newNodes: Seq[xml.Node] = Nil
            val key = new Key(currentCtxs, tNode)
            val nodesForContext = existing.get(key) match {
              case None if (result == null) => newNodes = if (skipNode) new SkipMarker else new InsertionMarker; newNodes
              case Some(m: SkipMarker) => m
              case Some(m: InsertionMarker) if (result == null) => if (skipNode) new SkipMarker else m
              case value @ (None | Some(_: InsertionMarker)) => {
                // create new node with transformed children
                val newChild = internalTransform(currentCtxs, tElem.child)
                val newElem = if (currentCtx == ctx) {
                  result.copy(child = newChild)
                } else {
                  new ElemWithRdfa(currentCtx, result.prefix, result.label, result.attributes,
                    result.scope, newChild: _*)
                }
                value match {
                  case Some(m: InsertionMarker) =>
                    if (replacedNodes == null) replacedNodes = new ReplacementMap
                    replacedNodes.put(m, newElem)
                  case _ => newNodes = newElem
                }
                newElem
              }
              case Some(nodes) if skipNode => {
                // force removal of already existing nodes
                if (replacedNodes == null) replacedNodes = new ReplacementMap
                nodes.foreach(replacedNodes.put(_, null))
                new SkipMarker
              }
              case Some(nodes) => {
                if (replacedNodes == null) replacedNodes = new ReplacementMap
                nodes.map(
                  _ match {
                    case e: Elem => {
                      var newE = e.copy(child = internalTransform(currentCtxs, tElem.child))
                      replacedNodes.put(e, newE)
                      newE
                    }
                    case other => other
                  })
              }
            }
            existing(key) = nodesForContext
            newNodes
          }
          case other => {
            val key = new Key(ctxs, tNode)
            existing.get(key) match {
              case Some(nodes) => Nil
              case None => {
                existing(key) = other
                other
              }
            }
          }
        }

        val key = new Key(ctxs, tNode, true)
        val nodesForTemplate = existing.getOrElse(key, Nil).flatMap {
          n =>
            (if (replacedNodes == null) None else replacedNodes.get(n)) match {
              case Some(replacement) => if (replacement != null) replacement else Nil
              case None => n
            }
        } ++ newNodesForTemplate
        existing(key) = nodesForTemplate

        nodesForTemplate
      })
    }

    internalTransform(List(ctx), template)
  }
}