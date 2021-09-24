package net.enilink.platform.ldp;

import net.enilink.composition.annotations.Iri;
import net.enilink.komma.core.URI;
import net.enilink.komma.model.IModel;
import net.enilink.platform.ldp.config.ContainerHandler;
import net.enilink.platform.ldp.config.RdfResourceHandler;
import net.enilink.platform.ldp.impl.OperationResponse;

import java.util.Set;

/**
 * LDP Container
 * <p>
 * "An LDP-RS representing a collection of linked documents or information
 * resources [...]"
 *
 * @see https://www.w3.org/TR/ldp/#h-terms
 * @see https://www.w3.org/TR/ldp/#ldpc
 */
@Iri("http://www.w3.org/ns/ldp#Container")
public interface LdpContainer extends LdpRdfSource {

	/**
	 * The relationship binding an LDPC to LDPRs whose lifecycle it controls and
	 * is aware of. The lifecycle of the contained LDPR is limited by the
	 * lifecycle of the containing LDPC; that is, a contained LDPR cannot be
	 * created (through LDP-defined means) before its containing LDPC exists.
	 *
	 * @see https://www.w3.org/TR/ldp/#dfn-containment
	 */
	@Iri("http://www.w3.org/ns/ldp#contains")
	Set<LdpResource> contains();

	void contains(Set<LdpResource> resources);

	OperationResponse createResource(IModel model, URI resourceType, RdfResourceHandler resourceHandler, ContainerHandler containerHandler, ReqBodyHelper body);
}
