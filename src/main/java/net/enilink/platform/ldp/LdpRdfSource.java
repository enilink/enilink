package net.enilink.platform.ldp;

import java.util.Set;

import net.enilink.composition.annotations.Iri;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.IStatement;

/**
 * LDP RDF Source (LDP-RS)
 * <p>
 * "An LDPR whose state is fully represented in RDF [...]"
 * 
 * @see https://www.w3.org/TR/ldp/#h-terms
 */
@Iri("http://www.w3.org/ns/ldp#RDFSource")
public interface LdpRdfSource extends LdpResource {

	/**
	 * Return the main type for the Link: $iri;rel=type header.<br>
	 * <b>Note:</b> Type ldp:Resource is added automatically.
	 */
	IReference getRelType();

	/**
	 * Return the relevant rdf-types for the resource.
	 */
	Set<IReference> getTypes();

	/**
	 * Return the list of statements for this RDFSource, with applied preferences.
	 */
	Set<IStatement> getTriples(int preferences);
}
