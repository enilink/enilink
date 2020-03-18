package net.enilink.platform.lift.rdfa

import scala.xml.NodeSeq
import scala.xml.UnprefixedAttribute
import scala.xml.NamespaceBinding

object RDFaHelpers {
  /**
   * Returns a value if the attribute with the given name is present and its trimmed text is not empty.
   */
  def nonempty(e: xml.Elem, name: String) = e.attribute(name) map (_.text.trim) filter (_.nonEmpty)

  def hasCssClass(e: xml.Elem, pattern: String) = nonempty(e, "class") exists { ("(?:^|\\s)" + pattern).r.findFirstIn(_).isDefined }
}