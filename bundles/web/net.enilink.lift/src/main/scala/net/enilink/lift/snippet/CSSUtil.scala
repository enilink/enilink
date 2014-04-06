package net.enilink.lift.snippet

import scala.xml.NodeSeq
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.LiftRules
import scala.xml.Unparsed
import net.liftweb.http.S
import net.enilink.lift.util.Globals

/**
 * Display Bootstrap CSS headers
 */
object CSSUtil extends DispatchSnippet {
  def dispatch: DispatchIt = {
    case "bootstrap" => _ => bootstrap
  }

  def bootstrap: NodeSeq = {
    // allow application specific versions of Bootstrap
    val prefix = "/" + LiftRules.resourceServerPath + Globals.applicationPath.vend + "bootstrap/css/"
    <xml:group>
      <link rel="stylesheet" href={
        prefix + "bootstrap.min.css"
      } type="text/css"/>
      <link rel="stylesheet" href={
        prefix + "bootstrap-theme.min.css"
      } type="text/css"/>
      <link rel="stylesheet" href={
        prefix + "typeahead-bootstrap.css"
      } type="text/css"/>
      <link rel="stylesheet" href={
        prefix + "bootstrap-custom.css"
      } type="text/css"/>
    </xml:group>
  }
}