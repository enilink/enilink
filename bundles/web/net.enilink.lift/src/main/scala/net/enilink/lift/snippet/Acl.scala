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
import net.enilink.vocab.acl.WEBACL
import net.enilink.vocab.rdf.RDF

/**
 * Snippet for inserting access control constraints into RDFa templates.
 */
object Acl {
  def render(ns: NodeSeq): NodeSeq = {
    var bindingVar = (ns \ "@data-for").text
    if (!bindingVar.isEmpty) {
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
              <span data-pattern={ "?_ acl:agent [ <http://xmlns.com/foaf/0.1/member>* ?currentUser ]" }/>
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