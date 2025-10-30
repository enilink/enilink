package net.enilink.platform.ldp.ldpatch.parse;

import net.enilink.komma.parser.sparql.tree.AbstractGraphNode;
import net.enilink.komma.parser.sparql.tree.Collection;

public class UpdateList implements Operation {
	private AbstractGraphNode subject;
	private AbstractGraphNode predicate;
	private Slice slice;
	private Collection collection;

	public UpdateList subject(AbstractGraphNode subject) {
		this.subject = subject;
		return this;
	}

	public AbstractGraphNode subject() {
		return this.subject;
	}

	public UpdateList predicate(AbstractGraphNode predicate) {
		this.predicate = predicate;
		return this;
	}

	public AbstractGraphNode predicate() {
		return this.predicate;
	}

	public UpdateList collection(Collection collection) {
		this.collection = collection;
		return this;
	}

	public Collection collection() {
		return this.collection;
	}

	public UpdateList slice(Slice slice) {
		this.slice = slice;
		return this;
	}

	public Slice slice() {
		return this.slice;
	}
}
