package net.enilink.platform.workbench.model;

import net.enilink.composition.annotations.Iri;
import net.enilink.vocab.rdf.Statement;

public interface ChangeStatement extends Statement {
	@Iri("http://enilink.net/vocab/komma#added")
	boolean isAdded();

	void setAdded(boolean added);

	@Iri("http://enilink.net/vocab/komma#index")
	int getIndex();

	void setIndex(int index);
}
