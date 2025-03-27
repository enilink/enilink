package net.enilink.platform.lift.snippet

import net.liftweb.common.{Box, Empty, Full}
import net.liftweb.http.S
import net.liftweb.util.Helpers._

import scala.xml.NodeSeq.seqToNodeSeq
import scala.xml.{Elem, NodeSeq}

class RdfaUtils {
  /**
   * Supports data-orderby attribute for automatic link created to enable users to change the variables and direction for sorting.
   */
  def orderby(ns: NodeSeq): NodeSeq = {
    val currentOrderBy = S.param("orderby") map { v =>
      v.split("\\s*!\\s*") match {
        case Array(key, direction @ ("asc" | "desc")) => (key, direction)
        case Array(key) => (key, "asc")
        case _ => (v, "asc")
      }
    }

    def createLink(e: Elem, varName: String, currentDir: Box[String]) = {
      val iconClass = "orderby-indicator glyphicon glyphicon-sort-by-attributes" + (if (currentDir.exists(_ == "asc")) "" else "-alt")
      var newChild = e.child
      if (currentDir.isDefined) newChild ++= <i class={ iconClass } style="margin-left: 1ex; font-size:80%"></i>
      val e2 = e.copy(attributes = e.attributes.remove("data-orderby"), child = newChild)
      val linkDir = if (currentDir.openOr("desc") == "asc") "desc" else "asc"
      val newParams = ParamsHelper.params(Set("orderby")) + ("orderby" -> (varName + (if (linkDir == "asc") "" else "!" + linkDir)))
      val link = <a href={ appendParams(S.uri, newParams.toSeq) }>{ e2 }</a>
      currentDir map { dir =>
        <xml:group>{ link }<span about={ "?" + varName } class={ dir }/></xml:group>
      } openOr link
    }

    def transform(ns: NodeSeq): NodeSeq = {
      ns flatMap {
        case e: Elem =>
          e.attribute("data-orderby") match {
            case Some(v) =>
              // example: var1!desc
              val (varName, defaultDir) = v.text.split("\\s*!\\s*") match {
                case Array(name, direction, _*) => (name.replaceAll("\\?", ""), Full(direction))
                case Array(name, _*) => (name.replaceAll("\\?", ""), Empty)
              }
              currentOrderBy match {
                case Full((key, dir)) => createLink(e, varName, if (key == varName) Full(dir) else Empty)
                case _ => createLink(e, varName, defaultDir)
              }
            case None => e.copy(child = transform(e.child))
          }
        case other => other
      }
    }
    transform(ns)
  }
}