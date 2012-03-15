package net.enilink.lift.snippet

import scala.xml.NodeSeq
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.LiftRules
import scala.xml.Unparsed
import net.liftweb.http.S

/**
 * Display Bootstrap CSS headers
 */
object CSSUtil extends DispatchSnippet {
  def dispatch: DispatchIt = {
    case "bootstrap" => _ => bootstrap
  }

  def bootstrap: NodeSeq = {
    <xml:group>
      <link rel="stylesheet" href={
        "/" + LiftRules.resourceServerPath +
          "/bootstrap/css/bootstrap.min.css"
      } type="text/css"/>
      <link rel="stylesheet" href={
        "/" + LiftRules.resourceServerPath +
          "/bootstrap/css/bootstrap-responsive.min.css"
      } type="text/css"/>
      <link rel="stylesheet" href={
        "/" + LiftRules.resourceServerPath +
          "/bootstrap/css/bootstrap-custom.css"
      } type="text/css"/>
    </xml:group>
  }
}