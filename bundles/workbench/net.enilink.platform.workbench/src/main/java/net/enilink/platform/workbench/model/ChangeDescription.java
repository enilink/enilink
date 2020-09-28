package net.enilink.platform.workbench.model;

import net.enilink.composition.annotations.Iri;
import net.enilink.vocab.komma.KOMMA;

import javax.xml.datatype.XMLGregorianCalendar;

@Iri(KOMMA.NAMESPACE + "ChangeDescription")
public interface ChangeDescription {

	XMLGregorianCalendar getDate();
	void setDate(XMLGregorianCalendar date);
}
