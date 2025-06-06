package net.enilink.platform.lift.snippet

import net.enilink.platform.lift.util.Globals
import net.liftweb.http.LiftRules

import scala.xml.NodeSeq

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