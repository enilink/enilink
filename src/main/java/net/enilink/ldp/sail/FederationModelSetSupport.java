package net.enilink.ldp.sail;

import java.util.Set;

import org.aopalliance.intercept.MethodInvocation;
import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.sail.SailRepository;
import org.openrdf.sail.federation.Federation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.enilink.composition.annotations.Iri;
import net.enilink.composition.annotations.ParameterTypes;
import net.enilink.composition.traits.Behaviour;
import net.enilink.komma.em.concepts.IResource;
import net.enilink.komma.model.IModelSet;
import net.enilink.komma.model.MODELS;
import net.enilink.komma.sesame.IRepositoryProvider;

@Iri(MODELS.NAMESPACE + "FederationModelSet")
public abstract class FederationModelSetSupport
		implements IModelSet, IModelSet.Internal, IRepositoryProvider, Behaviour<IRepositoryProvider> {

	protected final static Logger logger = LoggerFactory.getLogger(FederationModelSetSupport.class);

	public Repository createRepository() throws RepositoryException {
		logger.warn("createRepository() called");
		return null;
	}

	// TODO: make the internal repository (see LdpCache) configurable as well
	@ParameterTypes({})
	public Repository createRepository(MethodInvocation invocation) throws Throwable {
		logger.warn("createRepository(invocation={}) called", invocation);
		Repository baseRepository = null;
		try {
			baseRepository = (Repository) invocation.proceed();
			logger.warn("createRepository created baseRepository={}", baseRepository);
		} catch (Throwable t) {
			throw t;
		}

		Federation federation = new Federation();
		federation.addMember(new LdpCacheRepository());
		federation.addMember(baseRepository);

		for (IResource endpoint : getLdpEndpoints()) {
			logger.warn("createRepository got configured endpoint={}", endpoint);
			LdpCache.getInstance().addEndpoint(endpoint);
		}

		return new SailRepository(federation);
	}

	@Iri(MODELS.NAMESPACE + "ldpEndpoint")
	public abstract Set<IResource> getLdpEndpoints();
}
