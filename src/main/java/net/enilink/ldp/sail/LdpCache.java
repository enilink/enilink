package net.enilink.ldp.sail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openrdf.model.Resource;
import org.openrdf.model.URI;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.sail.SailRepository;
import org.openrdf.sail.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.enilink.komma.core.BlankNode;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.IStatement;
import net.enilink.komma.core.Statement;
import net.enilink.komma.core.URIs;
import net.enilink.komma.model.IModel;
import net.enilink.komma.model.IModelSet;
import net.enilink.vocab.rdf.RDF;

public class LdpCache {

	public final static URI ENDPOINT_MODEL_URI = new URIImpl("urn:enilink:ldp-endpoints");
	public final static URI TYPE_ENDPOINT = new URIImpl(ENDPOINT_MODEL_URI.stringValue() + "#Endpoint");
	public final static URI PROPERTY_HASADDRESS = new URIImpl(ENDPOINT_MODEL_URI.stringValue() + "#hasAddress");

	public final static URI CACHE_MODEL_URI = new URIImpl("urn:enilink:ldp-cache");

	protected final static Logger logger = LoggerFactory.getLogger(LdpCache.class);

	protected static LdpCache INSTANCE;

	protected Repository repository;
	protected RepositoryConnection connection;

	// FIXME: maybe use URIMapRules with a priority?
	protected Set<URI> endpoints;
	protected Map<URI, URI> lookupCache;

	protected IModel endpointModel;
	protected IModel cacheModel;

	public static LdpCache getInstance() {
		if (null == INSTANCE) {
			INSTANCE = new LdpCache();
		}
		return INSTANCE;
	}

	public static IModel getModel() {
		return getInstance().getCacheModel();
	}

	protected LdpCache() {
		try {
			// TODO: make this configurable (see FederationModelSetSupport)
			repository = new SailRepository(new MemoryStore());
			repository.initialize();
		} catch (RepositoryException re) {
			re.printStackTrace();
		}
		endpoints = new HashSet<URI>();

		// FIXME: deploy proper caching strategy
		lookupCache = new HashMap<URI, URI>(10000);
	}

	// FIXME: just a placeholder, the actual data isn't stored here, but in the
	// internal repository; this is needed for KOMMA model handling (context
	// triggers the federation's cache member and thus the internal repository)
	public IModel getCacheModel() {
		if (cacheModel == null) {
			IModelSet modelSet = Activator.getModelSet();
			if (null == modelSet) {
				return null;
			}
			net.enilink.komma.core.URI KOMMA_CACHE_MODEL_URI = URIs.createURI(CACHE_MODEL_URI.toString());
			cacheModel = modelSet.getModel(KOMMA_CACHE_MODEL_URI, false);
			if (null == cacheModel) {
				cacheModel = modelSet.createModel(KOMMA_CACHE_MODEL_URI);
			}
		}
		return cacheModel;
	}

	protected IModel getEndpointModel() {
		if (endpointModel == null) {
			IModelSet modelSet = Activator.getModelSet();
			if (null == modelSet) {
				return null;
			}
			// Activator creates this model
			net.enilink.komma.core.URI KOMMA_ENDPOINT_MODEL_URI = URIs.createURI(ENDPOINT_MODEL_URI.toString());
			endpointModel = modelSet.getModel(KOMMA_ENDPOINT_MODEL_URI, false);
		}
		return endpointModel;
	}

	public RepositoryConnection getConnection() throws RepositoryException {
		return repository.getConnection();
	}

	public void addEndpoint(IReference endpoint) {
		try {
			// might be called on initialization before modelset is available
			// hold off registering the endpoints with the model until later
			if (getEndpointModel() != null) {
				IReference node = new BlankNode();
				List<IStatement> stmts = new ArrayList<IStatement>();
				stmts.add(new Statement(node, RDF.PROPERTY_TYPE, URIs.createURI(TYPE_ENDPOINT.toString())));
				stmts.add(new Statement(node, URIs.createURI(PROPERTY_HASADDRESS.toString()), endpoint));
				getEndpointModel().getManager().add(stmts);
			}
			// when the modelset becomes available, the endpoints will be
			// registered with the endpoint model
			endpoints.add(new URIImpl(endpoint.toString()));
			logger.info("added LDP EP: {}", endpoint);
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	public Set<URI> getEndpoints() {
		return new HashSet<URI>(endpoints);
	}

	public URI getEndpoint(Resource resource) {
		if (null == resource || !(resource instanceof URI)) {
			return null;
		}
		URI uri = (URI) resource;
		if (lookupCache.containsKey(resource)) {
			return lookupCache.get(resource);
		}
		URI endpoint = null;
		// FIXME: can multiple endpoints be nested in sub-paths?
		// URIMapRules w/ priority could be used to resolve such cases properly
		for (URI ep : endpoints) {
			if (resource.toString().startsWith(ep.toString())) {
				endpoint = ep;
			}
		}
		lookupCache.put(uri, endpoint);

		logger.trace("LDP EP for resource '{}': {}", resource, endpoint);
		return endpoint;
	}
}
