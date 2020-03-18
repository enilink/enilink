package net.enilink.platform.lift.snippet

import scala.collection.mutable
import scala.xml.NodeSeq
import scala.xml.NodeSeq.seqToNodeSeq

/**
 * A simple tree snippet that converts any structure in the form of
 *
 * {{{
 * <ul data-lift="tree">
 * 	<li about="urn:id:node1">
 * 		Node 1
 * 		<ul>
 * 			<li class="child" about="urn:id:node2"></li>
 * 		</ul>
 * 	</li>
 * 	<li about="urn:id:node2">Node 2</li>
 * </ul>
 * }}}
 *
 * into a tree structure
 *
 * {{{
 * <ul>
 * 	<li about="urn:id:node1">
 * 		Node 1
 * 		<ul>
 * 			<li about="urn:id:node2">Node 2</li>
 * 		</ul>
 * 	</li>
 * </ul>
 * }}}
 *
 */
class Tree {
  def nonempty(e: xml.Elem, name: String) = e.attribute(name) map (_.text.trim) filter (_.nonEmpty)

  def hasCssClass(e: xml.Elem, pattern: String) = nonempty(e, "class") exists { ("(?:^|\\s)" + pattern).r.findFirstIn(_).isDefined }

  def id(n: xml.Node) = {
    val about = (n \ "@about")
    (if (about.isEmpty) (n \ "@resource") else about).text
  }

  def children(parent: xml.Node): Seq[xml.Node] = parent.child.flatMap {
    case e: xml.Elem if hasCssClass(e, "child") => e
    case e: xml.Elem => children(e)
    case other => Nil
  }

  def render(ns: NodeSeq): NodeSeq = ns map {
    case parent: xml.Elem => parent.copy(child = {
      val resource2Node = parent.child.map(e => (id(e), e)).toMap
      val allChildren = parent.child.flatMap { parent =>
        val parentIri = id(parent)
        children(parent).flatMap {
          child =>
            val childIri = id(child)
            if (parentIri != childIri) Some(childIri) else None
        }
      }.toSet

      var placed = mutable.Set.empty[String]
      def createTree(parent: xml.Node): xml.Node = parent match {
        case e: xml.Elem => e.copy(child = parent.child.map {
          case childTemplate: xml.Elem if hasCssClass(childTemplate, "child") => {
            val childIri = id(childTemplate)
            resource2Node.get(childIri).filter(_ => placed.add(childIri)).map(createTree(_)) getOrElse childTemplate
          }
          case other => createTree(other)
        })
        case other => other
      }

      val roots = parent.child.filter(n => !allChildren.contains(id(n)))
      roots.map(root => { placed.add(id(root)); createTree(root) })
    })
    case other => other
  }
}