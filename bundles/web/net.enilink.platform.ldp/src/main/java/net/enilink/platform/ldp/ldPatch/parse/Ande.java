package net.enilink.platform.ldp.ldPatch.parse;

import net.enilink.komma.parser.sparql.tree.GraphNode;

import java.util.List;

// super class for: Add, AddNew, Delete, DeleteExisting
public class Ande implements Operation {
	private List<GraphNode> subjects;

	public Ande graph(List<GraphNode> subjects) {

		this.subjects = subjects;
		return this;
	}

	public List<GraphNode> graph() {
		return subjects;
	}
}
