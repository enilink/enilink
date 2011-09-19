package net.enilink.lift.rdfa

import scala.xml.{ Elem, NodeSeq, Node, PrefixedAttribute }

import Util.combine

/**
 * BlankNodes are built from XML names, but their type is still abstract..
 */
trait RDFXMLNodeBuilder extends RDFNodeBuilder {
  def fresh(hint: String): BlankNode
  def byName(name: String): BlankNode
}