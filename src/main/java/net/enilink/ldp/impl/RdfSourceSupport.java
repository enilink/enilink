package net.enilink.ldp.impl;

import java.util.Set;

import net.enilink.composition.traits.Behaviour;
import net.enilink.komma.core.IQuery;
import net.enilink.komma.core.IStatement;
import net.enilink.komma.em.util.ISparqlConstants;
import net.enilink.ldp.LDP;
import net.enilink.ldp.LdpRdfSource;
import net.enilink.ldp.PreferenceHelper;

public abstract class RdfSourceSupport implements LdpRdfSource, Behaviour<LdpRdfSource> {

	@Override
	public Set<IStatement> getTriples(int preferences) {
		StringBuilder tmpltPatterns = new StringBuilder("" //
				+ "?r a ldp:RDFSource . " //
				+ "?r ?p ?o . " //
				// generation hints for the (Link: rel=type) header
				+ "?r tmp:rel-type ldp:Resource . "
				+ "?r tmp:rel-type ldp:RDFSource . ");
		StringBuilder graphPatterns = new StringBuilder("" //
				+ "?r a ldp:RDFSource . " //
				+ "?r ?p ?o . "); // FIXME: includes containment/membership!
		// add sub-containers to template...
		tmpltPatterns.append("?c a ldp:DirectContainer . "); // FIXME
		tmpltPatterns.append("?c ldp:membershipResource ?r . ");
		tmpltPatterns.append("?c ldp:hasMemberRelation ?mr . ");
		// ... and to graph patterns, but make them optional
		graphPatterns.append("OPTIONAL {");
		graphPatterns.append("?c a ldp:DirectContainer . "); // FIXME
		graphPatterns.append("?c ldp:membershipResource ?r . ");
		graphPatterns.append("?c ldp:hasMemberRelation ?mr . ");
		if ((preferences & PreferenceHelper.INCLUDE_CONTAINMENT) != 0) {
			tmpltPatterns.append("?c ldp:contains ?s . ");
			graphPatterns.append("?c ldp:contains ?s . ");
		}
		if ((preferences & PreferenceHelper.INCLUDE_MEMBERSHIP) != 0) {
			tmpltPatterns.append("?r ?mr ?m . ");
			graphPatterns.append("?r ?mr ?m . ");
		}
		graphPatterns.append("}");
		String queryStr = ISparqlConstants.PREFIX //
				// predicates using the tmp: prefix are hints for the header generation and
				// will need to be removed from the final triples
				+ "PREFIX tmp: <urn:temporary:generation-hint#> " //
				+ "PREFIX ldp: <" + LDP.NAMESPACE + "> " //
				+ "CONSTRUCT { " + tmpltPatterns.toString() + "} " //
				+ "WHERE { " + graphPatterns.toString() + "}";
		//System.out.println("executing for rs=" + getBehaviourDelegate() + " query=" + queryStr);
		IQuery<?> query = getEntityManager().createQuery(queryStr, false);
		query.setParameter("r", getBehaviourDelegate());
		return query.evaluate(IStatement.class).toSet();
	}
}
