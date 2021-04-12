package net.enilink.platform.ldp.impl;

import net.enilink.composition.traits.Behaviour;
import net.enilink.komma.core.*;
import net.enilink.komma.em.util.ISparqlConstants;
import net.enilink.komma.rdf4j.RDF4JValueConverter;
import net.enilink.platform.ldp.*;
import net.enilink.platform.ldp.config.DirectContainerHandler;
import net.enilink.platform.ldp.config.Handler;
import net.enilink.platform.ldp.config.RdfResourceHandler;
import net.enilink.vocab.rdf.RDF;
import net.enilink.vocab.xmlschema.XMLSCHEMA;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public abstract class RdfSourceSupport implements LdpRdfSource, Behaviour<LdpRdfSource> {

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
				+ "?this ?p ?o . "); // WARNING: includes containment/membership!

		// FILTER out any unwanted predicates (containment, membership)
		if ((preferences & PreferenceHelper.INCLUDE_CONTAINMENT) == 0) {
			// exclude containment predicate ldp:contains
			graphPatterns.append("  FILTER (?p != ldp:contains) ");
		}
		if ((preferences & PreferenceHelper.INCLUDE_MEMBERSHIP) == 0) {
			// exclude membership predicates: ours (?mRel) and those of sub-containers (?cRel)
			graphPatterns.append("  FILTER (!BOUND(?mRel) || ?p != ?mRel) ");
			graphPatterns.append("  FILTER (!BOUND(?cRel) || ?p != ?cRel) ");
		}

		// determine sub-containers, membership predicates and members
		// use a sub-select to be able to filter out membership predicates above
		graphPatterns.append("{ SELECT ?c ?cType ?cm ?mRes ?mRel ?cRes ?cRel WHERE {");

		// in case this is also a container
		// FIXME: find a better way w/o duplicating all of the rest
		tmpltPatterns.append("?this ldp:membershipResource ?mRes . ");
		// NOTE: this only handles (?container ldp:hasMemberRelation ?member)
		// but membership might be (?member ldp:isMemberOfRelation ?container)
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
		graphPatterns.append("}"); // optional: actual containment/membership
		graphPatterns.append("}"); // optional: membership predicate

		// also add any sub-resources that refer to ?this as their membershipResoure
		// these can be of type DirectContainer or IndirectContainer
		tmpltPatterns.append("?c a ?cType . ");
		tmpltPatterns.append("?c ldp:membershipResource ?this . ");
		// NOTE: this only handles (?container ldp:hasMemberRelation ?member)
		// but membership might be (?member ldp:isMemberOfRelation ?container)
		tmpltPatterns.append("?c ldp:hasMemberRelation ?cRel . ");
		// ... and to graph patterns, but make them optional
		graphPatterns.append("OPTIONAL {");
		graphPatterns.append("VALUES ?cType { ldp:DirectContainer ldp:IndirectContainer }");
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
		graphPatterns.append("}"); // optional: actual containment/membership
		graphPatterns.append("}"); // optional: sub-container and membership predicates
		graphPatterns.append("}}"); // sub-select
		String queryStr = ISparqlConstants.PREFIX //
				+ "PREFIX ldp: <" + LDP.NAMESPACE + "> " //
				+ "CONSTRUCT { " + tmpltPatterns.toString() + "} " //
				+ "WHERE { " + graphPatterns.toString() + "}";
		IQuery<?> query = getEntityManager().createQuery(queryStr, false);
		query.setParameter("this", getBehaviourDelegate());
		return query.evaluate(IStatement.class).toSet();
	}

	@Override
	public Set<LdpDirectContainer> membershipSourceFor() {
		String queryStr = ISparqlConstants.PREFIX //
				+ "PREFIX ldp: <" + LDP.NAMESPACE + "> " //
				+ "SELECT ?c {" //
				+ "  ?c ldp:membershipResource ?this . " //
				+ "}";
		IQuery<?> query = getEntityManager().createQuery(queryStr, false);
		query.setParameter("this", getBehaviourDelegate());
		return query.evaluate(LdpDirectContainer.class).toSet();
	}

	@Override
	public Map<Integer,String> preference(String preferenceHeader) {
		if (null != preferenceHeader) {
			List<String> prefs = Arrays.stream(preferenceHeader.split(";")).map(s -> s.trim()).collect(Collectors.toList());
			if (null != prefs && prefs.size() == 2) {
				Map<String, Integer> uriToPrefs = new HashMap<String, Integer>() {{
					put(LDP.PREFERENCE_MINIMALCONTAINER.toString(), PreferenceHelper.MINIMAL_CONTAINER);
					put(LDP.PREFERENCE_CONTAINMENT.toString(), PreferenceHelper.INCLUDE_CONTAINMENT);
					put(LDP.PREFERENCE_MEMBERSHIP.toString(), PreferenceHelper.INCLUDE_MEMBERSHIP);
				}};
				List<String> action = Arrays.stream(prefs.get(1).split("=")).map(s -> s.trim()).collect(Collectors.toList());
				if (null != action && action.size() == 2) {
					List<String> requests = Arrays.stream(action.get(1).split(" ")).map(s -> s.trim()).collect(Collectors.toList());
					if ("include".equals(action.get(0))) {
						int acc = 0;
						for (String p : requests) {
							Integer val = uriToPrefs.get(p.replace("\"", ""));
							if (null == val) {
								acc = PreferenceHelper.defaultPreferences();
								break;
							}
							acc = acc | uriToPrefs.get(p.replace("\"", ""));
						}
						if (acc > 0) return Collections.singletonMap(acc, prefs.get(0));
						return Collections.singletonMap(PreferenceHelper.defaultPreferences(), prefs.get(0));
					} else if ("omit".equals(action.get(0))) {
						int acc = PreferenceHelper.defaultPreferences();
						for (String p : requests) acc = acc - uriToPrefs.get(p.replace("\"", ""));
						if (acc != 0) return Collections.singletonMap(acc, prefs.get(0));
						return Collections.singletonMap(PreferenceHelper.MINIMAL_CONTAINER, prefs.get(0));
					}
					return Collections.singletonMap(PreferenceHelper.defaultPreferences(), prefs.get(0));
				}
			}
		}
		return Collections.singletonMap(PreferenceHelper.defaultPreferences(), null);
	}

	@Override
	public Map<Boolean, String> update( ReqBodyHelper body,  Handler handler){
		Set<IStatement> configStmts = null;
		if (null!= body && null != handler && !body.isBasicContainer() && !body.isDirectContainer()) {
			URI resourceUri = body.getURI();
			String msg = "";
			IEntityManager manager = getEntityManager();
			if (!(handler instanceof  RdfResourceHandler))
				msg = "wrong configurations, configuration will be ignored";
			else configStmts = matchConfig((RdfResourceHandler)handler, resourceUri);
			manager.removeRecursive(resourceUri, true);
			manager.add(new Statement(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_RDFSOURCE));
			if (null != configStmts) configStmts.forEach(stmt -> manager.add(stmt));
			RDF4JValueConverter valueConverter = body.valueConverter();
			body.getRdfBody().forEach(stmt -> {
				IReference subj = valueConverter.fromRdf4j(stmt.getSubject());
				IReference pred = valueConverter.fromRdf4j(stmt.getPredicate());
				IValue obj = valueConverter.fromRdf4j(stmt.getObject());
				if (subj != resourceUri || !body.isServerProperty(pred))
					manager.add(new Statement(subj, pred, obj));
			});
			manager.add(new Statement(resourceUri, LDP.DCTERMS_PROPERTY_MODIFIED,
					new Literal(Instant.now().toString(), XMLSCHEMA.TYPE_DATETIME)));
			return Collections.singletonMap(true,msg);
		}
		return Collections.singletonMap(false,"resource cannot be replaced, type mismatch");
	}

	@Override
	public Set<IStatement> matchConfig(RdfResourceHandler config, URI uri) {
		Set<IStatement> stmts = new HashSet<>();
		config.getTypes().forEach(t -> stmts.add(new Statement(uri, RDF.PROPERTY_TYPE, t)));
		DirectContainerHandler dh = config.getDirectContainerHandler();
		if (null != dh) {
			for (LdpDirectContainer dc : membershipSourceFor()) {
				dc.contains().forEach(r -> stmts.add(new Statement(uri, dc.hasMemberRelation(), r)));
				// special case
				stmts.add(new Statement(dc, LDP.PROPERTY_MEMBERSHIPRESOURCE, uri));
			}
		}
		return stmts;
	}
}
