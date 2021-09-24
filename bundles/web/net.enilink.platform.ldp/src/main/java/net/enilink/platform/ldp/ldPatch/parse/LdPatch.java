package net.enilink.platform.ldp.ldPatch.parse;

import net.enilink.komma.parser.sparql.tree.GraphNode;
import net.enilink.komma.parser.sparql.tree.PrefixDecl;
import net.enilink.komma.parser.sparql.tree.Prologue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LdPatch {
	protected Prologue prologue;
	protected List<Operation> tasks = new ArrayList<>();
	// Bind can override the value of a previously bound variable.
	protected Map<String, Bind> variables = new HashMap<>();
	protected Map<String, GraphNode> vars = new HashMap<>();

	public boolean setPrologue(Prologue prologue) {
		this.prologue = prologue;
		return true;
	}

	public boolean operations(List<Operation> ops) {
		this.tasks = ops;
		return true;
	}

	public List<Operation> operations() {
		return tasks;
	}

	public boolean addVariable(String name, Bind b) {
		this.variables.put(name, b);
		return true;
	}

	public Map<String, Bind> variables() {
		return variables;
	}

	public List<PrefixDecl> prologue() {
		return prologue.getPrefixDecls();
	}

}
