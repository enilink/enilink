package net.enilink.platform.ldp.sail;

import net.enilink.composition.annotations.Iri;
import net.enilink.composition.annotations.ParameterTypes;
import net.enilink.composition.traits.Behaviour;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;
import net.enilink.komma.em.concepts.IResource;
import net.enilink.komma.model.IModelSet;
import net.enilink.komma.model.MODELS;
import net.enilink.komma.model.rdf4j.IRepositoryModelSet;
import net.enilink.platform.ldp.remote.LdpCache;
import org.aopalliance.intercept.MethodInvocation;
import org.eclipse.rdf4j.federated.FedXFactory;
import org.eclipse.rdf4j.federated.endpoint.Endpoint;
import org.eclipse.rdf4j.federated.endpoint.EndpointFactory;
import org.eclipse.rdf4j.federated.repository.FedXRepository;
import org.eclipse.rdf4j.repository.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

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

		List<Endpoint> endpoints = List.of(
				EndpointFactory.loadEndpoint("base", baseRepository),
				EndpointFactory.loadEndpoint("ldp-cache", new LdpCacheRepository())
		);
		FedXRepository fedxRepository = FedXFactory.newFederation().withMembers(endpoints).create();

		for (IResource endpoint : getLdpEndpoints()) {
			LdpCache.addEndpoint(endpoint);
		}

		return fedxRepository;
	}

	@Override
	public URI getDefaultGraph() {
		return URIs.createURI("komma:default");
	}

	@Iri(MODELS.NAMESPACE + "ldpEndpoint")
	public abstract Set<IResource> getLdpEndpoints();
}
