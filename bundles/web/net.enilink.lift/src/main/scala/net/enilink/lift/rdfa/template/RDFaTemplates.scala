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

trait RDFaTemplates {
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

  private val rdfaAttributes = withPrefixes(List("data-", "data-clear-"), Set("about", "src", "rel", "rev", "property", "href", "resource", "content")) + "data-if"
  private val Variable = "^\\?(.*)".r

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

  /** Marks insertion points of new nodes if previous template transformations returned an empty result */
  class Marker extends Elem(null, "marker", new UnprefixedAttribute("class", "clearable", scala.xml.Null), xml.TopScope)

  final val attribute = "^(?:data(?:-clear)?-)?(.*)".r

  def transform(ctx: RdfContext, template: Seq[xml.Node])(implicit bindings: IBindings[_], existing: mutable.Map[Key, Seq[xml.Node]]): Seq[xml.Node] = {
    def internalTransform(ctxs: Seq[RdfContext], template: Seq[xml.Node]): Seq[xml.Node] = {
      var replacedNodes: mutable.Map[xml.Node, xml.Node] = null

      val ctx = ctxs.last
      val newNodesForTemplate = template.flatMap(tNode => {
        var currentCtx = ctx

        tNode match {
          case tElem: Elem => {
            val result = if (tElem.attributes.isEmpty) tElem else {
              var attributes = tElem.attributes
              tElem.attributes.foreach(meta =>
                if (attributes != null && !meta.isPrefixed && rdfaAttributes.contains(meta.key)) {
                  meta.value.text match {
                    case Variable(v) => {
                      val rdfValue = if (v == "this") ctx.subject else bindings.get(v)

                      if (rdfValue != null) {
                        // check if context needs to be changed for children
                        meta.key match {
                          case "data-if" => // does not change context
                          case attribute("rel" | "rev" | "property") =>
                            currentCtx = new RdfContext(currentCtx.subject, rdfValue)
                          case _ =>
                            currentCtx = new RdfContext(rdfValue, currentCtx.predicate)
                        }
                      }

                      val attValue = rdfValue match {
                        case ref: IReference => {
                          val uri = ref.getURI()
                          if (uri == null) ref else {
                            val namespace = uri.namespace.toString
                            lazy val uriStr = uri.toString
                            val prefix = tElem.scope.getPrefix(namespace)
                            if (prefix == null) uri else prefix + ":" + uriStr.substring(Math.min(namespace.length, uriStr.length))
                          }
                        }
                        case other => other
                      }

                      if (attValue == null) attributes = null
                      else if (meta.key.startsWith("data-clear-") || meta.key == "data-if")
                        attributes = attributes.remove(meta.key)
                      else attributes = attributes.append(new UnprefixedAttribute(meta.key, attValue.toString, meta.next))
                    }
                    case _ =>
                  }
                })
              if (attributes == null) null else tElem.copy(attributes = attributes)
            }

            val currentCtxs = if (currentCtx == ctx) ctxs else ctxs ++ List(currentCtx)

            var newNodes: Seq[xml.Node] = Nil
            val key = new Key(currentCtxs, tNode)
            val nodesForContext = existing.get(key) match {
              case None if (result == null) => newNodes = new Marker; newNodes
              case Some(m: Marker) if (result == null) => m
              case value @ (None | Some(_: Marker)) => {
                // create new node with transformed children
                val newChild = internalTransform(currentCtxs, tElem.child)
                val newElem = if (currentCtx == ctx) {
                  result.copy(child = newChild)
                } else {
                  new ElemWithRdfa(currentCtx, result.prefix, result.label, result.attributes,
                    result.scope, newChild: _*)
                }
                value match {
                  case Some(m: Marker) =>
                    if (replacedNodes == null) replacedNodes = new ReplacementMap
                    replacedNodes.put(m, newElem)
                  case _ => newNodes = newElem
                }
                newElem
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
      })

      val key = new Key(ctxs, template, true)
      val nodesForTemplate = existing.getOrElse(key, Nil).map {
        n =>
          (if (replacedNodes == null) None else replacedNodes.get(n)) match {
            case Some(replacement) => replacement
            case None => n
          }
      } ++ newNodesForTemplate
      existing(key) = nodesForTemplate

      nodesForTemplate
    }

    internalTransform(List(ctx), template)
  }
}