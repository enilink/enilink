package net.enilink.platform.ldp.impl;

import java.util.Collections;
import java.util.Set;

import net.enilink.composition.traits.Behaviour;
import net.enilink.komma.core.IQuery;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.IStatement;
import net.enilink.komma.em.util.ISparqlConstants;
import net.enilink.platform.ldp.LDP;
import net.enilink.platform.ldp.LdpRdfSource;
import net.enilink.platform.ldp.PreferenceHelper;
import net.enilink.platform.ldp.config.RdfResourceHandler;

public abstract class RdfSourceSupport implements LdpRdfSource, Behaviour<LdpRdfSource> {
	// apply default configuration 
	private RdfResourceHandler handler = new RdfResourceHandler();

	@Override
	public IReference getRelType() {
		return LDP.TYPE_RDFSOURCE;
	}

	@Override
	public Set<IReference> getTypes() {
		return Collections.singleton(LDP.TYPE_RDFSOURCE);
	}

	@Override
	public Set<IStatement> getTriples(int preferences) {
		StringBuilder tmpltPatterns = new StringBuilder("" //
				+ "?this a ?type . " //
				+ "?this ?p ?o . " //
		);
		getBehaviourDelegate().getTypes().forEach(t -> {
			tmpltPatterns.append("?this a <" + t.getURI().toString() + "> . ");
		});
		StringBuilder graphPatterns = new StringBuilder("" //
				+ "?this a ?type . " //
				+ "?this ?p ?o . "); // FIXME: includes containment/membership!
		// in case this is also a container
		// FIXME: find a better way w/o duplicating all of the rest
		tmpltPatterns.append("?this ldp:membershipResource ?mRes . ");
		tmpltPatterns.append("?this ldp:hasMemberRelation ?mRel . ");
		graphPatterns.append("OPTIONAL {");
		graphPatterns.append("?this ldp:membershipResource ?mRes . ");
		graphPatterns.append("?this ldp:hasMemberRelation ?mRel . ");
		// also add containment and membership triples
		graphPatterns.append("OPTIONAL {");
		if ((preferences & PreferenceHelper.INCLUDE_CONTAINMENT) != 0) {
			tmpltPatterns.append("?this ldp:contains ?m . ");
			graphPatterns.append("?this ldp:contains ?m . "); // FIXME: use membership relation?
		}
		if ((preferences & PreferenceHelper.INCLUDE_MEMBERSHIP) != 0) {
			tmpltPatterns.append("?mRes ?mRel ?m . ");
			graphPatterns.append("?mRes ?mRel ?m . ");
		}
		graphPatterns.append("}");
		graphPatterns.append("}");

		// also add any sub-resources that refer to ?this as their membershipResoure
		tmpltPatterns.append("?c a ?cType . ");
		tmpltPatterns.append("?c ldp:membershipResource ?this . ");
		tmpltPatterns.append("?c ldp:hasMemberRelation ?cRel . ");
		// ... and to graph patterns, but make them optional
		graphPatterns.append("VALUES ?cType { ldp:BasicContainer ldp:DirectContainer }");
		graphPatterns.append("OPTIONAL {");
		graphPatterns.append("?c a ?cType . ");
		graphPatterns.append("?c ldp:membershipResource ?this . ");
		graphPatterns.append("?c ldp:hasMemberRelation ?cRel . ");
		// also add containment and membership triples
		graphPatterns.append("OPTIONAL {");
		if ((preferences & PreferenceHelper.INCLUDE_CONTAINMENT) != 0) {
			tmpltPatterns.append("?c ldp:contains ?cm . ");
			graphPatterns.append("?c ldp:contains ?cm . ");
		}
		if ((preferences & PreferenceHelper.INCLUDE_MEMBERSHIP) != 0) {
			tmpltPatterns.append("?this ?cRel ?cm . ");
			graphPatterns.append("?this ?cRel ?cm . ");
		}
		graphPatterns.append("}");
		graphPatterns.append("}");
		String queryStr = ISparqlConstants.PREFIX //
				+ "PREFIX ldp: <" + LDP.NAMESPACE + "> " //
				+ "CONSTRUCT { " + tmpltPatterns.toString() + "} " //
				+ "WHERE { " + graphPatterns.toString() + "}";
		IQuery<?> query = getEntityManager().createQuery(queryStr, false);
		query.setParameter("this", getBehaviourDelegate());
		return query.evaluate(IStatement.class).toSet();
	}
	
	@Override
	public RdfResourceHandler getHandler() {return handler;}
	
	@Override
	public void setHandler(RdfResourceHandler handler) { this.handler = handler;}
}
