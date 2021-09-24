package net.enilink.platform.ldp.impl;

import net.enilink.commons.iterator.IExtendedIterator;
import net.enilink.composition.traits.Behaviour;
import net.enilink.komma.core.*;
import net.enilink.komma.em.util.ISparqlConstants;
import net.enilink.komma.parser.sparql.tree.AbstractGraphNode;
import net.enilink.komma.parser.sparql.tree.GraphNode;
import net.enilink.komma.parser.sparql.tree.PrefixDecl;
import net.enilink.komma.parser.sparql.tree.PropertyPattern;
import net.enilink.komma.rdf4j.RDF4JValueConverter;
import net.enilink.platform.ldp.*;
import net.enilink.platform.ldp.config.DirectContainerHandler;
import net.enilink.platform.ldp.config.Handler;
import net.enilink.platform.ldp.config.RdfResourceHandler;
import net.enilink.platform.ldp.ldPatch.parse.*;
import net.enilink.vocab.rdf.RDF;
import net.enilink.vocab.xmlschema.XMLSCHEMA;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.impl.TreeModel;

import java.time.Instant;
import java.util.*;

public abstract class RdfSourceSupport implements LdpRdfSource, Behaviour<LdpRdfSource> {

	private static final URI TYPE_LIST = URIs.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#List");

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
				+ "CONSTRUCT { " + tmpltPatterns + "} " //
				+ "WHERE { " + graphPatterns + "}";
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
	public OperationResponse update(ReqBodyHelper body, Handler handler) {
		Set<IStatement> configStmts = null;
		if (null != body && null != handler && !body.isBasicContainer() && !body.isDirectContainer()) {
			URI resourceUri = body.getURI();
			String msg = "";
			IEntityManager manager = getEntityManager();
			if (!(handler instanceof RdfResourceHandler))
				msg = "wrong configurations, configuration will be ignored";
			else configStmts = matchConfig((RdfResourceHandler) handler, resourceUri);
			manager.removeRecursive(resourceUri, true);
			manager.add(new Statement(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_RDFSOURCE));
			if (null != configStmts) configStmts.forEach(stmt -> manager.add(stmt));
			RDF4JValueConverter valueConverter = ReqBodyHelper.valueConverter();
			body.getRdfBody().forEach(stmt -> {
				IReference subj = valueConverter.fromRdf4j(stmt.getSubject());
				IReference pred = valueConverter.fromRdf4j(stmt.getPredicate());
				IValue obj = valueConverter.fromRdf4j(stmt.getObject());
				if (subj != resourceUri || !body.isServerProperty(pred))
					manager.add(new Statement(subj, pred, obj));
			});
			manager.add(new Statement(resourceUri, LDP.DCTERMS_PROPERTY_MODIFIED,
					new Literal(Instant.now().toString(), XMLSCHEMA.TYPE_DATETIME)));
			return new OperationResponse(OperationResponse.OK, msg);
		}
		return new OperationResponse(OperationResponse.CONFLICT, "resource cannot be replaced, type mismatch");
	}

	@Override
	public OperationResponse updatePartially(LdPatch ldPatch) {
		if (null == ldPatch)
			return new OperationResponse(OperationResponse.UNPROCESSED_ENTITY, "LD Patch Parser Error");
		// resolve variables if any
		Map<String, Bind> vars = ldPatch.variables();
		System.out.println("vars to resolve: " + vars.keySet());
		HashMap<String, IValue> resolvedVariables = null;
		if (null != vars && !vars.isEmpty()) {
			resolvedVariables = new HashMap<>();
			for (Map.Entry e : vars.entrySet()) {
				Bind b = (Bind) e.getValue();
				/*
				https://www.w3.org/TR/ldpatch/#grammar-production-predicateObjectList
				After being bound, the variable can be used in the subsequent statements: not allowed to use variable before declaration
				 */
				//if (null == b || resolvedVariables.containsKey(b.getName())) continue;
				OperationResponse result = resolveVariable(b, resolvedVariables, ldPatch);
				if (result.hasError()) return result;
				List<IValue> values = (List<IValue>) result.valueOf(OperationResponse.ValueType.IVALUES);
				if (values == null || values.size() != 1)
					return new OperationResponse(" Bind statement fails to match exactly one node for variable: " + b.getName());
				resolvedVariables.put(b.getName(), values.get(0));
				System.out.println(" the variable: " + b.getName() + " resolved to value: " + values.get(0));
			}
			System.out.println("resolved vars: " + resolvedVariables);
		}
		//the server must apply the entire set of changes atomically
		Set<Statement> toBeAdded = new HashSet<>();
		Set<Statement> toBeAddedNew = new HashSet<>();
		Set<Statement> toBEDeleted = new HashSet<>();
		Set<Statement> toBeDeletedIfExist = new HashSet<>();
		Set<Statement> toBeUpdated = new HashSet<>();
		IReference toBeCut = null;
		for (Operation op : ldPatch.operations()) {
			if (op instanceof UpdateList) {
				System.out.println("processing UpdateList");
				OperationResponse opResult = updateList((UpdateList) op, resolvedVariables, ldPatch.prologue());
				if (opResult.hasError()) return opResult;
				Set<Statement> stmts = (Set<Statement>) opResult.valueOf(OperationResponse.ValueType.STATEMENTS);
				toBeUpdated.addAll(stmts);
				continue;
			}
			if (op instanceof Add) {
				OperationResponse opResult = getStatementsFromGraph((Add) op, ldPatch.prologue(), resolvedVariables);
				if (opResult.hasError()) return opResult;
				Set<Statement> stmts = (Set<Statement>) opResult.valueOf(OperationResponse.ValueType.STATEMENTS);
				toBeAdded.addAll(stmts);
				continue;
			}
			if (op instanceof AddNew) {
				OperationResponse opResult = getStatementsFromGraph((AddNew) op, ldPatch.prologue(), resolvedVariables);
				if (opResult.hasError()) return opResult;
				Set<Statement> stmts = (Set<Statement>) opResult.valueOf(OperationResponse.ValueType.STATEMENTS);
				toBeAddedNew.addAll(stmts);
				continue;
			}
			if (op instanceof Delete) {
				OperationResponse opResult = getStatementsFromGraph((Delete) op, ldPatch.prologue(), resolvedVariables);
				if (opResult.hasError()) return opResult;
				Set<Statement> stmts = (Set<Statement>) opResult.valueOf(OperationResponse.ValueType.STATEMENTS);
				toBEDeleted.addAll(stmts);
				continue;
			}
			if (op instanceof DeleteExisting) {
				OperationResponse opResult = getStatementsFromGraph((DeleteExisting) op, ldPatch.prologue(), resolvedVariables);
				if (opResult.hasError()) return opResult;
				Set<Statement> stmts = (Set<Statement>) opResult.valueOf(OperationResponse.ValueType.STATEMENTS);
				toBeDeletedIfExist.addAll(stmts);
				continue;
			}
			if (op instanceof Cut) {
				String varName = ((Cut) op).variable().getName();
				if (!resolvedVariables.containsKey(varName))
					return new OperationResponse(OperationResponse.BAD_REQ, "a variable \"" + varName + "\" is used without being previously bound");
				if (!(resolvedVariables.get(varName) instanceof IReference))
					return new OperationResponse(OperationResponse.UNPROCESSED_ENTITY, "Cut operation is called on a variable not bound to a blank node");
				IReference varValue = (IReference) resolvedVariables.get(varName);
				if (varValue.getURI() != null)
					return new OperationResponse(OperationResponse.UNPROCESSED_ENTITY, "Cut operation is called on a variable not bound to a blank node");
				if (!getEntityManager().hasMatch(null, null, varValue))
					return new OperationResponse(OperationResponse.UNPROCESSED_ENTITY, " Cut operation fails to remove any triple");
				toBeCut = varValue;
			}
		}
		// update model
		System.out.println("updated list: " + toBeUpdated);
		toBeUpdated.forEach(st -> {
			List<IStatement> l = getEntityManager().match(st.getSubject(), st.getPredicate(), null).toList();
			for (IStatement s : l) getEntityManager().removeRecursive(s.getObject(), true);
			getEntityManager().add(st);
		});
		System.out.println("statements to be added: " + toBeAdded);
		System.out.println("statements to be added new: " + toBeAddedNew);
		System.out.println("statements to be deleted: " + toBEDeleted);
		getEntityManager().add(toBeAdded);
		getEntityManager().add(toBeAddedNew);
		getEntityManager().remove(toBEDeleted);
		getEntityManager().remove(toBeDeletedIfExist);
		if (toBeCut != null)
			getEntityManager().removeRecursive(toBeCut, true);

		// success
		return new OperationResponse(OperationResponse.OK, "PATCH succeed");
	}

	private OperationResponse getStatementsFromGraph(Ande op, List<PrefixDecl> prologue, Map<String, IValue> resolvedVariables) {
		Set<Statement> stmts = new HashSet<>();
		for (GraphNode node : op.graph()) {
			OperationResponse subj = ParseHelper.resolveNode(prologue, node, getURI(), resolvedVariables);
			if (subj.hasError()) return subj;
			IReference subject = (IReference) subj.valueOf(OperationResponse.ValueType.IVALUE);
			for (PropertyPattern predNode : node.getPropertyList()) {
				OperationResponse pred = ParseHelper.resolveNode(prologue, predNode.getPredicate(), getURI(), resolvedVariables);
				if (pred.hasError()) return pred;
				IReference predicate = (IReference) pred.valueOf(OperationResponse.ValueType.IVALUE);
				OperationResponse obj = ParseHelper.resolveNode(prologue, predNode.getObject(), getURI(), resolvedVariables);
				if (obj.hasError()) return obj;
				Object object = obj.valueOf(OperationResponse.ValueType.IVALUE);
				if (op instanceof AddNew && getEntityManager().hasMatch(subject, predicate, object))
					return new OperationResponse(OperationResponse.UNPROCESSED_ENTITY, "attempts to add an already existing triple");
				if (op instanceof DeleteExisting && !getEntityManager().hasMatch(subject, predicate, object))
					return new OperationResponse(OperationResponse.UNPROCESSED_ENTITY, "attempts to remove a non-existing triple");
				stmts.add(new Statement(subject, predicate, object));
				//adding blank node case
				if ((op instanceof Add || op instanceof AddNew) && object instanceof BlankNode) {
					OperationResponse bnStmts = ParseHelper.addBlankNode((BlankNode) object, predNode.getObject().getPropertyList(), prologue, getURI(), resolvedVariables);
					if (bnStmts.hasError()) return bnStmts;
					stmts.addAll((Set<Statement>) bnStmts.valueOf(OperationResponse.ValueType.STATEMENTS));
				}

			}
		}
		return new OperationResponse(stmts);
	}


	/*
        https://www.w3.org/TR/ldpatch/#grammar-production-predicateObjectList
        After being bound, the variable can be used in the subsequent statements: not allowed to use variable before declaration
     */
	private OperationResponse resolveVariable(Bind bind, HashMap<String, IValue> resolvedVariables, LdPatch ldPatch) {
		OperationResponse node = ParseHelper.resolveNode(ldPatch.prologue(), bind.getValue(), getURI(), resolvedVariables);
		if (node.hasError()) return node;
		IValue value = (IValue) node.valueOf(OperationResponse.ValueType.IVALUE);
		if (null == bind.getPath() || null == bind.getPath().getElements() || bind.getPath().getElements().isEmpty())
			return new OperationResponse(value);
		return resolvePath(bind.getPath(), value, ldPatch, true, resolvedVariables);
	}

	private OperationResponse resolvePath(Path path, List<IValue> values, Model model, LdPatch ldPatch, boolean forward, HashMap<String, IValue> resolvedVariables) {
		return resolvePath(path, values, model, null, ldPatch, forward, resolvedVariables);
	}

	private OperationResponse resolvePath(Path path, IValue start, LdPatch ldPatch, boolean forward, HashMap<String, IValue> resolvedVariables) {
		return resolvePath(path, null, new TreeModel(), start, ldPatch, forward, resolvedVariables);
	}

	private OperationResponse resolvePath(Path path, List<IValue> iValues, Model model, IValue start, LdPatch ldPatch, boolean forward, HashMap<String, IValue> resolvedVariables) {
		if (path == null) return new OperationResponse(start);
		RDF4JValueConverter valueConverter = ReqBodyHelper.valueConverter();
		List<IValue> vals = iValues;
		URI predicate = null;
		for (PathElement pe : path.getElements()) {
			if (pe instanceof Step) {
				int stp = ((Step) pe).step();
				int s = forward ? stp : -1 * stp;
				AbstractGraphNode iri = ((Step) pe).iri();
				System.out.println("path element of type Step: step is " + s);
				if (null != iri) {
					OperationResponse node = ParseHelper.resolveNode(ldPatch.prologue(), iri, getURI(), resolvedVariables);
					if (node.hasError()) return node;
					IValue iriVal = (IValue) node.valueOf(OperationResponse.ValueType.IVALUE);
					if (!(iriVal instanceof IReference)) return new OperationResponse("wrong type of Predicate ");
					predicate = (URI) iriVal;
					if (start != null) {
						System.out.println("resolve path from start, Step with iri");
						vals = getNode(start, predicate, s);
						for (IValue v : vals) {
							if (!(v instanceof IReference)) break;
							List<IStatement> stmts = getEntityManager().match((IReference) v, null, null).toList();
							for (IStatement st : stmts)
								model.add(valueConverter.toRdf4j(st));
						}
						System.out.print("model: {");
						for (org.eclipse.rdf4j.model.Statement st : model)
							System.out.print("( " + st.getSubject() + ", " + st.getPredicate() + ", " + st.getObject() + "), ");
						System.out.println("}");
						if (model == null || model.isEmpty())
							System.out.println("model empty, vals: " + vals);
						System.out.println("resolve path from start, end Step with iri, vals: " + vals);
					} else {
						System.out.println("resolve path from constraint, Step with iri, vals: " + vals + ", vals has predicate: " + predicate);
						model = model.filter(null, valueConverter.toRdf4j(predicate), null);
					}

				} else {
					if (null != start) {
						System.out.println("resolve path from start, Step without iri");
						if (s > 0 && !(start instanceof IReference)) {
							System.out.println("resolving path from start, Step without iri (index), start: " + start + " not instance of IRfefence");
							return new OperationResponse("Error in Variable definition");
						}
						vals = getNode(start, null, s);
						for (IValue v : vals)
							model.add((Resource) valueConverter.toRdf4j(v), valueConverter.toRdf4j(predicate), valueConverter.toRdf4j(start));
						System.out.println("resolving path from start with Index, step= " + s + ", vals: " + vals + ", model: " + model);
					} else {
						System.out.println("resolve path from constarint with Index, step = " + s + ", model: " + model + ", vals: " + iValues);
						if (iValues.size() != 1)
							return new OperationResponse("Error in Variable definition");
						vals = getNode(iValues.get(0), null, s);
						IExtendedIterator<IStatement> stmts = null;
						if (iValues.get(0) instanceof IReference)
							stmts = getEntityManager().match((IReference) iValues.get(0), null, null);
						for (IStatement st : stmts)
							model.add(valueConverter.toRdf4j(st));
						System.out.println("resolve path from constarint with Index end, step = " + s + ", model: " + model + ", vals: " + vals);

					}
				}
			} else if (pe instanceof UnicityConstraint) {
				System.out.println("Unicity constraint: model: " + model + ", ivalues: " + iValues + ", vals: " + vals);
				if (model == null || vals == null || vals.size() != 1)
					return new OperationResponse("Unicity Constraint not fulfilled");

			} else if (pe instanceof FilterConstraint) {
				if (model == null) return new OperationResponse("Error in Variable definition");
				FilterConstraint cons = (FilterConstraint) pe;
				IValue equalToVal = null;
				if (cons.value() != null) {
					OperationResponse equalTo = ParseHelper.resolveNode(ldPatch.prologue(), cons.value(), getURI(), resolvedVariables);
					if (equalTo.hasError()) return equalTo;
					equalToVal = (IValue) equalTo.valueOf(OperationResponse.ValueType.IVALUE);
				}
				System.out.println("cons has path, step: " + cons.path().step() + ", equal value = " + equalToVal);
				OperationResponse consPath = resolvePath(cons.path(), vals, model, ldPatch, false, resolvedVariables);
				if (consPath.hasError()) return consPath;
				Model m = (Model) consPath.valueOf(OperationResponse.ValueType.MODEL);
				URI pred = (URI) consPath.valueOf(OperationResponse.ValueType.IVALUE);
				System.out.println("Filter Constraint, path resolved to vals: " + model);
				if (cons.path().step() < 0) {
					if (!(equalToVal instanceof IReference))
						return new OperationResponse("Error in Variable definition");
					model = m.filter(valueConverter.toRdf4j((IReference) equalToVal), valueConverter.toRdf4j(pred), null);
				} else {
					System.out.println("resolve path from constraint with start = " + start + ", subject: " + pred + ", equal value: " + equalToVal + ", model: " + model);
					model = m.filter(null, valueConverter.toRdf4j(pred), valueConverter.toRdf4j(equalToVal));
				}
				System.out.println("resolve path, Equality Constraint on: " + model + ", value: " + equalToVal);
			}
		}
		System.out.println("resolve path: start:" + start + ", model: " + model);
		List<IValue> values = new ArrayList<>();
		for (org.eclipse.rdf4j.model.Statement stmt : model) {
			IReference subj = valueConverter.fromRdf4j(stmt.getSubject());
			if (!values.contains(subj))
				values.add(valueConverter.fromRdf4j(stmt.getSubject()));
		}
		System.out.println("vals: " + vals + ", values from model: " + values);
		if (start == null) return new OperationResponse(model, predicate);
		return new OperationResponse(values);
	}

	private List<IValue> getNode(IValue ref1, IReference pred, int step) {
		System.out.println("subj: " + ref1 + ", pred: " + pred + ", step: " + step);
		String queryStr = null;
		if (step == 1)
			queryStr = "SELECT DISTINCT ?o WHERE { ?s ?p ?o }";
		if (step == -1)
			queryStr = "SELECT DISTINCT ?s WHERE { ?s ?p ?o }";
		IQuery<?> query = getEntityManager().createQuery(queryStr);
		System.out.println("query: " + query);
		if (step == 1) query.setParameter("s", ref1);
		if (step == -1) query.setParameter("o", ref1);
		query.setParameter("p", pred);
		List<IValue> vals = query.evaluate(IValue.class).toList();
		return vals;
	}

	private OperationResponse updateList(UpdateList ul, HashMap<String, IValue> resolvedVariables, List<PrefixDecl> prologue) {
		OperationResponse s = ParseHelper.resolveNode(prologue, ul.subject(), getURI(), resolvedVariables);
		if (s.hasError()) return s;
		OperationResponse p = ParseHelper.resolveNode(prologue, ul.predicate(), getURI(), resolvedVariables);
		if (p.hasError()) return p;
		if (!(s.valueOf(OperationResponse.ValueType.IVALUE) instanceof IReference) || !(p.valueOf(OperationResponse.ValueType.IVALUE) instanceof IReference))
			return new OperationResponse("Subject or Predicate error Definition in UpdateList Operation");
		IReference sPre = (IReference) s.valueOf(OperationResponse.ValueType.IVALUE);
		IReference pPre = (IReference) p.valueOf(OperationResponse.ValueType.IVALUE);
		IQuery<?> q = getEntityManager().createQuery("SELECT DISTINCT ?o WHERE { ?s ?p ?o }");
		q.setParameter("s", sPre);
		q.setParameter("p", pPre);
		List<List> object = q.evaluate(List.class).toList();
		if (null == object || object.size() != 1)
			return new OperationResponse("UpdateList Error, the list is not a unique well-formed collection ");
		List<IValue> oPre = (List<IValue>) object.get(0);
		int min = ul.slice().min() != Integer.MIN_VALUE ? ul.slice().min() : oPre.size();
		int max = ul.slice().max() != Integer.MAX_VALUE ? ul.slice().max() : oPre.size();
		if (min < 0) min = oPre.size() + min;
		if (max < 0) max = oPre.size() + max;
		if (min > max || min < 0)
			return new OperationResponse(OperationResponse.BAD_REQ, "UpdateList Error:  slice expression are in the wrong order");
		System.out.println("UpdateList: min = " + min + ", max = " + max + ", s: " + sPre + ", p: " + pPre + ", o: " + oPre);
		System.out.print("O ( size: " + oPre.size());
		System.out.print(", elems: ");
		for (int i = 0; i < oPre.size(); i++) System.out.print(oPre.get(i) + ", ");
		System.out.println(")");
		int collSize = ul.collection() == null || ul.collection().getElements() == null || ul.collection().getElements().isEmpty() ? 0 : ul.collection().getElements().size();
		int newObjSize = min + (oPre.size() - max) + collSize;
		OperationResponse collObj = ParseHelper.resolveNode(prologue, ul.collection(), getURI(), resolvedVariables);
		if (collObj.hasError()) return collObj;
		List<IValue> coll = ((List<IValue>) collObj.valueOf(OperationResponse.ValueType.IVALUES));
		System.out.println("new items to be added to list: " + coll + ", new list size = " + newObjSize);
		for (int i = 0; i < coll.size(); i++) oPre.add(min + i, coll.get(i));
		for (int j = min, k = 0; j < max; j++, k++) oPre.remove(j + coll.size() - k);
		Set<Statement> stmts = new HashSet<>();
		List<IValue> list = Lists.create(getEntityManager());
		list.addAll(oPre);
		stmts.add(new Statement(sPre, pPre, list));
		return new OperationResponse(stmts);
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
