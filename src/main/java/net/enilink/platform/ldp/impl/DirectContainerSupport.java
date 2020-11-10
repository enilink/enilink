package net.enilink.platform.ldp.impl;

import java.util.Set;

import net.enilink.composition.annotations.Precedes;
import net.enilink.composition.traits.Behaviour;
import net.enilink.komma.core.IQuery;
import net.enilink.komma.core.IStatement;
import net.enilink.komma.em.util.ISparqlConstants;
import net.enilink.platform.ldp.LDP;
import net.enilink.platform.ldp.LdpDirectContainer;
import net.enilink.platform.ldp.PreferenceHelper;

@Precedes(RdfSourceSupport.class)
public abstract class DirectContainerSupport implements LdpDirectContainer, Behaviour<LdpDirectContainer> {

	@Override
	public Set<IStatement> getTriples(int preferences) {
		StringBuilder tmpltPatterns = new StringBuilder("" //
				+ "?c a ldp:Container . " //
				+ "?c a ldp:DirectContainer . " //
				+ "?c ?p ?o . " //
				+ "?c ldp:membershipResource ?r . " //
				+ "?c ldp:hasMemberRelation ?mr . " //
				// generation hints for the (Link: rel=type) header
				+ "?c tmp:rel-type ldp:Resource . " //
				+ "?c tmp:rel-type ldp:DirectContainer . ");
		StringBuilder graphPatterns = new StringBuilder("" //
				+ "?c a ldp:DirectContainer . " //
				+ "?c ?p ?o . " // FIXME: includes containment/membership!
				+ "?c ldp:membershipResource ?r . " //
				+ "?c ldp:hasMemberRelation ?mr . ");
		graphPatterns.append("OPTIONAL {");
		if ((preferences & PreferenceHelper.INCLUDE_CONTAINMENT) != 0) {
			tmpltPatterns.append("?c ldp:contains ?s . ");
			graphPatterns.append("?c ldp:contains ?s . "); // FIXME: use membership relation?
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
		//System.out.println("executing for dc=" + getBehaviourDelegate() + " query=" + queryStr);
		IQuery<?> query = getEntityManager().createQuery(queryStr, false);
		query.setParameter("c", getBehaviourDelegate());
		return query.evaluate(IStatement.class).toSet();
	}
}
