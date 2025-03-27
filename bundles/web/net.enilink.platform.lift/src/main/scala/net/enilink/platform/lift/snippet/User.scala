package net.enilink.platform.lift.snippet

import net.enilink.komma.core.URI
import net.enilink.platform.lift.util.Globals

import scala.xml._

object User {
  private def replace(n: Node) = n.attributes.isEmpty || n.attributes.size == 1 && n.attribute("data-t").isDefined

  def name(ns: NodeSeq): NodeSeq = {
    val username = Text(Globals.contextUser.vend match {
      case uri: URI => uri.localPart
      case _ => ""
    })
    ns flatMap { n => if (replace(n)) username else n.asInstanceOf[Elem].copy(child = username) }
  }
}