package net.enilink.ldp.sail;

import java.util.Set;

import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.sail.SailRepository;
import org.openrdf.sail.federation.Federation;

import net.enilink.composition.annotations.Iri;
import net.enilink.komma.em.concepts.IResource;
import net.enilink.komma.model.MODELS;
import net.enilink.komma.model.sesame.MemoryModelSetSupport;

@Iri(MODELS.NAMESPACE + "FederationModelSet")
public abstract class FederationModelSetSupport extends MemoryModelSetSupport {

	// TODO: make the base repository configurable
	// TODO: make the internal repository (see LDPCache) configurable as well
	public Repository createRepository() throws RepositoryException {
		Repository baseRepository = super.createRepository();

		Federation federation = new Federation();
		federation.addMember(new LdpCacheRepository());
		federation.addMember(baseRepository);

		for (IResource endpoint : getLdpEndpoints()) {
			LdpCache.getInstance().addEndpoint(endpoint);
		}

		return new SailRepository(federation);
	}

	@Iri(MODELS.NAMESPACE + "ldpEndpoint")
	public abstract Set<IResource> getLdpEndpoints();
}
