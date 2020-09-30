package net.enilink.platform.workbench.model;

import net.enilink.composition.annotations.Iri;
import net.enilink.vocab.foaf.Agent;
import net.enilink.vocab.komma.KOMMA;
import net.enilink.vocab.rdf.Statement;

import javax.xml.datatype.XMLGregorianCalendar;
import java.util.Set;

@Iri(KOMMA.NAMESPACE + "ChangeDescription")
public interface ChangeDescription {
	@Iri("http://purl.org/dc/terms/date")
	XMLGregorianCalendar getDate();

	void setDate(XMLGregorianCalendar date);

	@Iri("http://enilink.net/vocab/komma#agent")
	Agent getAgent();

	void setAgent(Agent agent);

	@Iri("http://enilink.net/vocab/komma#added")
	Set<Statement> getAdded();

	void setAdded(Set<Statement> added);

	@Iri("http://enilink.net/vocab/komma#removed")
	Set<Statement> getRemoved();

	void setRemoved(Set<Statement> removed);
}
