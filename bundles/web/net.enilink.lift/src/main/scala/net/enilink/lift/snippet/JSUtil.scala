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
  }

  def bootstrap: NodeSeq = {
    <script src={
      "/" + LiftRules.resourceServerPath +
        "/bootstrap/js/bootstrap.min.js"
    } type="text/javascript"></script>
  }

  def rdfa: NodeSeq = {
    <script src={
      "/" + LiftRules.resourceServerPath +
        "/rdfa/jquery.rdfquery.rdfa.js"
    } type="text/javascript"></script>
  }
}