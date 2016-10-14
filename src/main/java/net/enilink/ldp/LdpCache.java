package net.enilink.ldp;

import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.security.auth.Subject;

import org.openrdf.model.IRI;
import org.openrdf.model.Resource;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.SimpleValueFactory;
import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.sail.SailRepository;
import org.openrdf.sail.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.enilink.core.security.SecurityUtil;
import net.enilink.komma.core.BlankNode;
import net.enilink.komma.core.IQuery;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.IStatement;
import net.enilink.komma.core.Statement;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;
import net.enilink.komma.em.concepts.IResource;
import net.enilink.komma.model.IModel;
import net.enilink.komma.model.IModelSet;
import net.enilink.vocab.rdf.RDF;

public class LdpCache {

	public final static URI ENDPOINT_MODEL_URI = URIs.createURI("enilink:model:ldp:endpoints");

	public final static URI TYPE_ENDPOINT = ENDPOINT_MODEL_URI.appendFragment("Endpoint");
	public final static URI PROPERTY_HASADDRESS = ENDPOINT_MODEL_URI.appendFragment("hasAddress");

	protected final static ValueFactory vf = SimpleValueFactory.getInstance();

	// this is used from LdpClient et al. w/ RDF4J directly, not w/ KOMMA
	public final static IRI CACHE_MODEL_IRI = vf.createIRI("enilink:model:ldp:cache");

	protected final static Logger logger = LoggerFactory.getLogger(LdpCache.class);

	protected static LdpCache INSTANCE;
	protected static Set<URI> endpoints;

	protected IModelSet modelSet;

	protected Repository repository;
	protected RepositoryConnection connection;

	// FIXME: maybe use URIMapRules with a priority?
	protected Map<IRI, IRI> lookupCache;

	protected IModel endpointModel;
	protected IModel cacheModel;

	public static LdpCache getInstance() {
		return INSTANCE;
	}

	public static IModel getModel() {
		return getInstance().cacheModel;
	}

	public LdpCache() {
		endpoints = new HashSet<URI>();
		// FIXME: deploy proper caching strategy
		lookupCache = new HashMap<IRI, IRI>(10000);
	}

	/**
	 * Called from OSGi-DS upon component activation (w/o configuration).
	 */
	protected void activate() {
		try {
			// TODO: make this configurable
			repository = new SailRepository(new MemoryStore());
			repository.initialize();
			logger.trace("LdpCache initialized MemoryStore cache");
		} catch (RepositoryException re) {
			throw new IllegalStateException("Failed to initialize LdpCache repository: " + re);
		}
		INSTANCE = LdpCache.this;
		Subject.doAs(SecurityUtil.SYSTEM_USER_SUBJECT, new PrivilegedAction<Void>() {
			@Override
			public Void run() {
				modelSet.getUnitOfWork().begin();
				try {
					// FIXME: just a placeholder, the actual data isn't stored
					// here, but in the internal repository; this is needed for
					// KOMMA model handling (context triggers the federation's
					// cache member and thus the internal repository)
					URI CACHE_MODEL_URI = URIs.createURI(CACHE_MODEL_IRI.toString());
					cacheModel = modelSet.getModel(CACHE_MODEL_URI, false);
					if (null == cacheModel) {
						cacheModel = modelSet.createModel(CACHE_MODEL_URI);
					}

					// create a model to hold the configured endpoints
					Set<URI> newEndpoints = getEndpoints();
					endpointModel = modelSet.getModel(ENDPOINT_MODEL_URI, false);
					if (null == endpointModel) {
						endpointModel = modelSet.createModel(ENDPOINT_MODEL_URI);
						((IResource) endpointModel).setRdfsLabel("registered LDP endpoints");
					} else {
						// sync endpoints between map and model
						// query the model for known endpoints
						IQuery<?> query = endpointModel.getManager()
								.createQuery("PREFIX le: <" + ENDPOINT_MODEL_URI + "#> " //
										+ "SELECT ?epAddr WHERE {" //
										+ " ?ep a le:Endpoint . " //
										+ " ?ep le:hasAddress ?epAddr . " //
										+ "}", false);
						// register the old endpoints from the model
						for (IReference endpointAddress : query.evaluateRestricted(IReference.class).toList()) {
							addEndpoint(endpointAddress);
						}
						query.close();
					}

					// put the new endpoints into the model
					List<IStatement> stmts = new ArrayList<IStatement>();
					for (URI newEndpoint : newEndpoints) {
						IReference node = new BlankNode();
						stmts.add(new Statement(node, RDF.PROPERTY_TYPE, URIs.createURI(TYPE_ENDPOINT.toString())));
						stmts.add(new Statement(node, URIs.createURI(PROPERTY_HASADDRESS.toString()), newEndpoint));
					}
					try {
						endpointModel.getManager().add(stmts);
					} catch (Throwable t) {
						t.printStackTrace();
					}

					return null;
				} catch (Throwable t) {
					throw t;
				} finally {
					modelSet.getUnitOfWork().end();
				}
			}
		});
		logger.trace("LdpCache activated");
	}

	/**
	 * Called from OSGi-DS upon component de-activation.
	 */
	protected void deactivate() {
	}

	/**
	 * Binds the ModelSet. Called by OSGi-DS.
	 * 
	 * @param modelSet
	 */
	protected void setModelSet(IModelSet modelSet) {
		// FIXME: do handle the dynamic nature properly
		this.modelSet = modelSet;
	}

	/**
	 * Unbinds the ModelSet. Called by OSGi-DS.
	 * 
	 * @param modelSet
	 */
	protected void unsetModelSet(IModelSet modelSet) {
		// FIXME: do handle the dynamic nature properly
		this.modelSet = null;
	}

	public RepositoryConnection getConnection() throws RepositoryException {
		return repository.getConnection();
	}

	// FIXME! problematic invocation order, this is called from
	// FederationModelSetSupport
	public static void addEndpoint(IReference endpoint) {
		// when the modelset becomes available, the endpoints will be
		// registered with the endpoint model
		endpoints.add(endpoint.getURI());
		logger.info("added LDP EP: {}", endpoint);
	}

	public static Set<URI> getEndpoints() {
		return new HashSet<URI>(endpoints);
	}

	public IRI getEndpoint(Resource resource) {
		if (null == resource || !(resource instanceof IRI)) {
			return null;
		}
		IRI iri = (IRI) resource;
		if (lookupCache.containsKey(resource)) {
			return lookupCache.get(resource);
		}
		IRI endpoint = null;
		// FIXME: can multiple endpoints be nested in sub-paths?
		// URIMapRules w/ priority could be used to resolve such cases properly
		for (URI ep : endpoints) {
			if (resource.toString().startsWith(ep.toString())) {
				endpoint = vf.createIRI(ep.toString());
			}
		}
		lookupCache.put(iri, endpoint);

		logger.trace("LDP EP for resource '{}': {}", resource, endpoint);
		return endpoint;
	}
}
