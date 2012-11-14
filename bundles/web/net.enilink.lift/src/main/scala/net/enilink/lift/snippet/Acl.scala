package net.enilink.lift.snippet

import scala.xml.Elem
import scala.xml.NodeSeq
import scala.xml.Text
import scala.xml.Text
import net.enilink.komma.core.IEntityManager
import net.enilink.lift.rdfa.SparqlFromRDFa
import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.http.PaginatorSnippet
import net.liftweb.util.CssSel
import net.liftweb.util.Helpers._
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers.strToCssBindPromoter
import net.liftweb.http.Templates
import net.liftweb.common.Full
import net.liftweb.http.S
import net.enilink.vocab.acl.ACL
import net.enilink.vocab.rdf.RDF

/**
 * Snippet for inserting access control constraints into RDFa templates.
 */
object Acl {
  def render(ns: NodeSeq): NodeSeq = {
    var bindingVar = (ns \ "@data-for").text
    if (!bindingVar.isEmpty) {
      <span xmlns:acl={ ACL.NAMESPACE } xmlns:rdf={ RDF.NAMESPACE } class="exists union clearable">
        <!-- user either owns the target resource -->
        <span about={ bindingVar } rel="acl:owner" resource="?currentUser"/>
        <!-- or has at least read access to it -->
        <span typeof="acl:Authorization">
          <span class="union">
            <span rel="acl:accessTo" resource={ bindingVar }/>
            <span rel="acl:accessToClass">
              <span rev="rdf:type" resource={ bindingVar }/>
            </span>
          </span>
          <span rel="acl:mode" resource="acl:Read"/>
          <span class="union">
            <span rel="acl:agent" resource="?currentUser"/>
            <span rel="acl:agentClass">
              <span rev="rdf:type" resource="?agent"/>
            </span>
          </span>
        </span>
      </span>
    } else ns
  }
}