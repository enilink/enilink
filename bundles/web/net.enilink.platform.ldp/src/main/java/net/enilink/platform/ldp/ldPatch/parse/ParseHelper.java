package net.enilink.platform.ldp.ldPatch.parse;


import net.enilink.komma.core.*;
import net.enilink.komma.parser.sparql.tree.Collection;
import net.enilink.komma.parser.sparql.tree.*;
import net.enilink.komma.parser.sparql.tree.visitor.ToStringVisitor;
import net.enilink.platform.ldp.impl.OperationResponse;
import net.enilink.vocab.xmlschema.XMLSCHEMA;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

public class ParseHelper {
	public static ILiteral resolveLiteral(net.enilink.komma.parser.sparql.tree.Literal literal) {
		LiteralFactory bc = new LiteralFactory();
		if (literal instanceof BooleanLiteral) {
			Boolean value = ((BooleanLiteral) literal).getValue();
			return bc.createLiteral(value.toString(), XMLSCHEMA.TYPE_BOOLEAN, null);
		}
		if (literal instanceof DoubleLiteral) {
			Double value = ((DoubleLiteral) literal).getValue();
			return bc.createLiteral(value.toString(), XMLSCHEMA.TYPE_DOUBLE, null);
		}
		if (literal instanceof IntegerLiteral) {
			Integer value = ((IntegerLiteral) literal).getValue();
			return bc.createLiteral(value.toString(), XMLSCHEMA.TYPE_INTEGER, null);
		} else {
			StringBuilder lStr = new StringBuilder();
			literal.accept(new ToStringVisitor(), lStr);
			String value = StringUtils.strip(lStr.toString(), "\"");
			return bc.createLiteral(value, XMLSCHEMA.TYPE_STRING, null);
		}
	}

	public static OperationResponse resolveNode(List<PrefixDecl> prologue, GraphNode node, URI base, Map<String, IValue> resolvedVariables) {
		if (node instanceof QName) {
			QName qName = ((QName) node);
			Optional<PrefixDecl> iri = prologue.stream().filter(p ->
					p.getPrefix().equals(qName.getPrefix())
			).findFirst();
			if (iri.isPresent()) {
				return new OperationResponse(URIs.createURI(iri.get().getIri().getIri()).appendLocalPart(qName.getLocalPart()));
			}
			return new OperationResponse(OperationResponse.BAD_REQ, "a prefix name \"" + qName.getPrefix() + "\" is used without being previously declared");
		}
		if (node instanceof IriRef) {
			URI uriBase = base.toString().endsWith("/") ? base.trimSegments(1) : base;
			URI uri = URIs.createURI(((IriRef) node).getIri());
			return new OperationResponse(uri.resolve(uriBase));
		}
		if (node instanceof Variable) {
			String varName = ((Variable) node).getName();
			if (!resolvedVariables.containsKey(varName))
				return new OperationResponse(OperationResponse.BAD_REQ, "a variable \"" + varName + "\" is used without being previously bound");
			return new OperationResponse(resolvedVariables.get(varName));
		}
		if (node instanceof net.enilink.komma.parser.sparql.tree.Literal) {
			return new OperationResponse(resolveLiteral((net.enilink.komma.parser.sparql.tree.Literal) node));
		}
		if (node instanceof Collection) {
			Collection coll = (Collection) node;
			List<IValue> values = new ArrayList<>();
			for (GraphNode n : coll.getElements()) {
				OperationResponse result = resolveNode(prologue, n, base, resolvedVariables);
				if (result.hasError()) return result;
				values.add((IValue) result.valueOf(OperationResponse.ValueType.IVALUE));
			}
			return new OperationResponse(values);
		}
		if (node instanceof BNode) {
			String label = ((BNode) node).getLabel();
			BlankNode bn = label == null ? new BlankNode() : new BlankNode(label);
			return new OperationResponse(bn);
		}
		return new OperationResponse(OperationResponse.UNPROCESSED_ENTITY, "could not resolve Graph Node");
	}

	public static OperationResponse addBlankNode(BlankNode object, PropertyList pl, List<PrefixDecl> prologue, URI uri, Map<String, IValue> resolvedVariables) {
		Set<Statement> stmts = new HashSet<>();
		for (PropertyPattern p : pl) {
			OperationResponse bnPredResult = resolveNode(prologue, p.getPredicate(), uri, resolvedVariables);
			if (bnPredResult.hasError()) return bnPredResult;
			Object bnPred = bnPredResult.valueOf(OperationResponse.ValueType.IVALUE);
			if (!(bnPred instanceof IReference))
				return new OperationResponse(OperationResponse.BAD_REQ, "wrong predicate definition in a to be added blank node ");
			OperationResponse bnObjResult = ParseHelper.resolveNode(prologue, p.getObject(), uri, resolvedVariables);
			if (bnObjResult.hasError()) return bnObjResult;
			Object bnObj = bnObjResult.valueOf(OperationResponse.ValueType.IVALUE);
			if (bnObj instanceof BlankNode) {
				OperationResponse childBNResult = addBlankNode((BlankNode) bnObj, p.getObject().getPropertyList(), prologue, uri, resolvedVariables);
				if (childBNResult.hasError()) return childBNResult;
				Set<Statement> bnStmts = (Set<Statement>) childBNResult.valueOf(OperationResponse.ValueType.STATEMENTS);
				stmts.addAll(bnStmts);
			}
			stmts.add(new Statement(object, (IReference) bnPred, bnObj));
		}
		return new OperationResponse(stmts);
	}
}
