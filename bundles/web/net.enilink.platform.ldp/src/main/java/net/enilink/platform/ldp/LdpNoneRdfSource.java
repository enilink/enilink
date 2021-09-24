package net.enilink.platform.ldp;

import net.enilink.composition.annotations.Iri;
import net.enilink.komma.core.URI;

@Iri("http://www.w3.org/ns/ldp#NonRDFSource")
public interface LdpNoneRdfSource extends LdpResource {
	@Iri("http://purl.org/dc/terms/identifier")
	URI identifier();

	void identifier(URI id);

	@Iri("http://purl.org/dc/terms/format")
	String format();

	void format(String f);

	@Iri("http://purl.org/dc/terms/title")
	String fileName();

	void fileName(String name);

	// Map<Boolean, String> create()

}
