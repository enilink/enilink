package net.enilink.ldp.sail;

import java.util.Set;

import org.aopalliance.intercept.MethodInvocation;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.federation.Federation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.enilink.composition.annotations.Iri;
import net.enilink.composition.annotations.ParameterTypes;
import net.enilink.composition.traits.Behaviour;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;
import net.enilink.komma.em.concepts.IResource;
import net.enilink.komma.model.IModelSet;
import net.enilink.komma.model.MODELS;
import net.enilink.komma.model.rdf4j.IRepositoryModelSet;
import net.enilink.ldp.LdpCache;

@Iri(MODELS.NAMESPACE + "FederationModelSet")
public abstract class FederationModelSetSupport
		implements IModelSet, IModelSet.Internal, IRepositoryModelSet, Behaviour<IRepositoryModelSet> {

	protected final static Logger logger = LoggerFactory.getLogger(FederationModelSetSupport.class);

	// TODO: make the internal repository (see LdpCache) configurable as well
	@ParameterTypes({})
	public Repository createRepository(MethodInvocation invocation) throws Throwable {
		Repository baseRepository = null;
		try {
			baseRepository = (Repository) invocation.proceed();
			logger.trace("createRepository created baseRepository={}", baseRepository);
		} catch (Throwable t) {
			throw t;
		}

		Federation federation = new Federation();
		federation.addMember(new LdpCacheRepository());
		federation.addMember(baseRepository);

		for (IResource endpoint : getLdpEndpoints()) {
			LdpCache.addEndpoint(endpoint);
		}

		return new SailRepository(federation);
	}

	@Override
	public URI getDefaultGraph() {
		return URIs.createURI("komma:default");
	}

	@Iri(MODELS.NAMESPACE + "ldpEndpoint")
	public abstract Set<IResource> getLdpEndpoints();
}
