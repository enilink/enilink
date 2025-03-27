package net.enilink.platform.lift.snippet

import scala.xml.NodeSeq

import net.enilink.vocab.acl.WEBACL
import net.enilink.vocab.rdf.RDF
import net.liftweb.util.Helpers

/**
 * Snippet for inserting access control constraints into RDFa templates.
 */
object Acl {
  def render(ns: NodeSeq): NodeSeq = {
    val bindingVar = (ns \ "@data-for").text
    if (bindingVar.nonEmpty) {
      val agentVar = "?" + Helpers.nextFuncName
      <span prefix={ "acl: " + WEBACL.NAMESPACE + " rdf: " + RDF.NAMESPACE } class="clearable">
        <span class="exists union" data-filter="bound(?currentUser)">
          <!-- user either owns the target resource -->
          <span about={ bindingVar } rel="acl:owner" resource="?currentUser"/>
          <!-- or has at least read access to it -->
          <span about="?">
            <span class="union">
              <span rel="acl:accessTo" resource={ bindingVar }/>
              <span rel="acl:accessToClass">
                <span rev="rdf:type" resource={ bindingVar }/>
              </span>
            </span>
            <span rel="acl:mode" class="union">
              <span resource="acl:Read"/>
              <span resource="acl:Control"/>
              <span resource="http://enilink.net/vocab/acl#WriteRestricted"/>
            </span>
            <span class="union">
              <!-- support "normal" agents and agent groups -->
              <span rel="acl:agent" resource={ agentVar }>
                <span data-pattern={ s"$agentVar <http://xmlns.com/foaf/0.1/member>* ?currentUser" }/>
              </span>
              <!-- use may belong to a agent class -->
              <span rel="acl:agentClass">
                <span rev="rdf:type" resource="?currentUser"/>
              </span>
            </span>
          </span>
        </span>
      </span>
    } else ns
  }
}