package net.enilink.lift.snippet

import scala.xml.NodeSeq
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.LiftRules
import scala.xml.Unparsed
import net.liftweb.http.S

/**
 * Snippets for embedding of JS scripts.
 */
object JSUtil extends DispatchSnippet {
  def dispatch: DispatchIt = {
    case "bootstrap" => _ => bootstrap
    case "rdfa" => _ => rdfa
    case "edit" => _ => edit
  }

  private def script(src: String) = <script src={ src } type="text/javascript" data-lift="head"></script>

  def bootstrap: NodeSeq = List("bootstrap.min", "bootstrap-ext").flatMap {
    lib => script("/" + LiftRules.resourceServerPath + "/bootstrap/js/" + lib + ".js")
  }

  def rdfa: NodeSeq = script("/" + LiftRules.resourceServerPath + "/rdfa/jquery.rdfquery.rdfa.js")

  def edit: NodeSeq = List("jquery.autogrow", "jquery.caret").flatMap {
    lib => script("/" + LiftRules.resourceServerPath + "/edit/" + lib + ".js")
  }
}