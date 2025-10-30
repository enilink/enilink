package net.enilink.platform.ldp.ldpatch.parse;

import net.enilink.komma.parser.sparql.tree.AbstractGraphNode;

public class Step implements PathElement {
	private int step = 1;
	private final AbstractGraphNode iri;

	public Step(int step, AbstractGraphNode iri) {
		this.step = step;
		this.iri = iri;
	}

	public int step() {
		return step;
	}

	public AbstractGraphNode iri() {
		return iri;
	}
}
