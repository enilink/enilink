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
object CSSUtil {
  def bootstrap: NodeSeq = {
    // allow application specific versions of Bootstrap
    val prefix = "/" + LiftRules.resourceServerPath + Globals.applicationPath.vend + "bootstrap/css/"
    <xml:group>
      <link rel="stylesheet" href={
        prefix + "bootstrap.min.css"
      } type="text/css"/>
      <link rel="stylesheet" href={
        prefix + "bootstrap-enilink.css"
      } type="text/css"/>
      <link rel="stylesheet" href={
        prefix + "typeahead-bootstrap.css"
      } type="text/css"/>
      <link rel="stylesheet" href={
        prefix + "bootstrap-custom.css"
      } type="text/css"/>
    </xml:group>
  }

  def materialDesignIcons: NodeSeq = {
    <link rel="stylesheet" href={
      "/" + LiftRules.resourceServerPath + "/material-design-iconic-font/css/material-design-iconic-font.css"
    } type="text/css" data-lift="head"/>
  }
}