package net.enilink.platform.ldp;

import net.enilink.composition.annotations.Iri;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.IStatement;
import net.enilink.komma.core.URI;
import net.enilink.platform.ldp.config.Handler;
import net.enilink.platform.ldp.config.RdfResourceHandler;

import java.util.Map;
import java.util.Set;

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
	
	Set<LdpDirectContainer> membershipSourceFor();

	Map<Boolean, String> update(ReqBodyHelper body, Handler config);

	Map<Integer,String> preference(String preferenceHeader);

	Set<IStatement> matchConfig(RdfResourceHandler config, URI uri);
}
