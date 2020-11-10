package net.enilink.platform.ldp.impl;

import java.util.Set;

import net.enilink.composition.annotations.Precedes;
import net.enilink.composition.traits.Behaviour;
import net.enilink.komma.core.IQuery;
import net.enilink.komma.core.IStatement;
import net.enilink.komma.em.util.ISparqlConstants;
import net.enilink.platform.ldp.LDP;
import net.enilink.platform.ldp.LdpBasicContainer;
import net.enilink.platform.ldp.PreferenceHelper;

@Precedes(RdfSourceSupport.class)
public abstract class BasicContainerSupport implements LdpBasicContainer, Behaviour<LdpBasicContainer> {

	@Override
	public Set<IStatement> getTriples(int preferences) {
		StringBuilder templatePatterns = new StringBuilder("" //
				+ "?c a ldp:Container . " //
				+ "?c a ldp:BasicContainer . " //
				+ "?c ?p ?o . " //
				// generation hints for the (Link: rel=type) header
				+ "?c tmp:rel-type ldp:Resource . " //
				+ "?c tmp:rel-type ldp:BasicContainer . ");
		StringBuilder graphPatterns = new StringBuilder("" //
				+ "?c a ldp:BasicContainer . " //
				+ "?c ?p ?o . "); // FIXME: includes containment/membership!
		if ((preferences & PreferenceHelper.INCLUDE_CONTAINMENT) != 0) {
			templatePatterns.append("?c ldp:contains ?r . ");
			graphPatterns.append("?c ldp:contains ?r . ");
		}
		String queryStr = ISparqlConstants.PREFIX //
				// predicates using the tmp: prefix are hints for the header generation and
				// will need to be removed from the final triples
				+ "PREFIX tmp: <urn:temporary:generation-hint#> " //
				+ "PREFIX ldp: <" + LDP.NAMESPACE + "> " //
				+ "CONSTRUCT { " + templatePatterns.toString() + "} " //
				+ "WHERE { " + graphPatterns.toString() + "}";
		//System.out.println("executing for bc=" + getBehaviourDelegate() + " query=" + queryStr);
		IQuery<?> query = getEntityManager().createQuery(queryStr, false);
		query.setParameter("c", getBehaviourDelegate());
		return query.evaluate(IStatement.class).toSet();
	}
}
