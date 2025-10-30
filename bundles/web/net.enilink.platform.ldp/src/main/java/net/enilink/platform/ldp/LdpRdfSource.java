package net.enilink.platform.ldp;

import net.enilink.composition.annotations.Iri;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.IStatement;
import net.enilink.komma.core.URI;
import net.enilink.platform.ldp.config.Handler;
import net.enilink.platform.ldp.config.RdfResourceHandler;
import net.enilink.platform.ldp.impl.OperationResponse;
import net.enilink.platform.ldp.ldpatch.parse.LdPatch;

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

	/**
	 * Return the the direct containers for which this RDFSource configured as Source, if any
	 */
	Set<LdpDirectContainer> membershipSourceFor();

	/**
	 * to which containers should this resource belongs.
	 */
	@Iri(LDP.NAMESPACE + "resourceContainer")
	LdpContainer getContainer();

	void setContainer(LdpContainer container);

	OperationResponse update(ReqBodyHelper body, Handler config);

	OperationResponse updatePartially(LdPatch ldpatch);

	Set<IStatement> matchConfig(RdfResourceHandler config, URI uri);
}
