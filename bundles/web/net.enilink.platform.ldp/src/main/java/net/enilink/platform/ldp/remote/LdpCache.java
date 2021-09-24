package net.enilink.platform.ldp.remote;

import net.enilink.commons.iterator.IMap;
import net.enilink.komma.core.*;
import net.enilink.komma.em.concepts.IResource;
import net.enilink.komma.model.IModel;
import net.enilink.komma.model.IModelSet;
import net.enilink.komma.rdf4j.RDF4JValueConverter;
import net.enilink.platform.core.PluginConfigModel;
import net.enilink.platform.core.security.SecurityUtil;
import net.enilink.vocab.owl.OWL;
import net.enilink.vocab.rdf.RDF;
import org.eclipse.rdf4j.common.iteration.Iterations;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.Subject;
import java.security.PrivilegedAction;
import java.util.*;

public class LdpCache {

	public final static URI PLUGIN_CONFIG_URI = URIs.createURI("plugin://net.enilink.platform.ldp/");

	public final static URI ENDPOINT_MODEL_URI = URIs.createURI("enilink:model:ldp:endpoints");

	public final static URI TYPE_ENDPOINT = ENDPOINT_MODEL_URI.appendFragment("Endpoint");
	public final static URI PROPERTY_HASADDRESS = ENDPOINT_MODEL_URI.appendFragment("hasAddress");

	protected final static ValueFactory vf = SimpleValueFactory.getInstance();
	protected final static RDF4JValueConverter vc = new RDF4JValueConverter(vf);

	// this is used from LdpClient et al. w/ RDF4J directly, not w/ KOMMA
	public final static IRI CACHE_MODEL_IRI = vf.createIRI("enilink:model:ldp:cache");

	protected final static Logger logger = LoggerFactory.getLogger(LdpCache.class);

	protected static LdpCache INSTANCE;
	protected static Set<URI> endpoints;

	protected IModelSet modelSet;

	protected boolean useExtraRepository = true;
	protected Repository repository;
	protected RepositoryConnection connection;

	// FIXME: maybe use URIMapRules with a priority?
	protected Map<IRI, IRI> lookupCache;

	protected IModel endpointModel;
	protected IModel cacheModel;

	public static LdpCache getInstance() {
		return INSTANCE;
	}

	public LdpCache() {
		endpoints = new HashSet<URI>();
		// FIXME: deploy proper caching strategy
		lookupCache = new HashMap<IRI, IRI>(10000);
	}

	/**
	 * Called from OSGi-DS upon component activation (w/o configuration).
	 */
	public void activate() {
		try {
			if (useExtraRepository) {
				// TODO: make this configurable
				repository = new SailRepository(new MemoryStore());
				repository.init();
				logger.trace("LdpCache initialized internal MemoryStore repository.");
			}
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
					// add the LDP cache as readable graph to the modelset
					modelSet.getModule().addReadableGraph(CACHE_MODEL_URI);

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
						// add a statement about the ontology into the cache (so that it isn't empty)
						getConnection().add(vf.createStatement(CACHE_MODEL_IRI, vc.toRdf4j(RDF.PROPERTY_TYPE),
								vc.toRdf4j(OWL.TYPE_ONTOLOGY)), CACHE_MODEL_IRI);
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
		INSTANCE = null;
		logger.trace("LdpCache deactivated");
	}

	/**
	 * Binds the ModelSet. Called by OSGi-DS.
	 *
	 * @param modelSet
	 */
	public void setModelSet(IModelSet modelSet) {
		// FIXME: do handle the dynamic nature properly
		this.modelSet = modelSet;
	}

	/**
	 * Unbinds the ModelSet. Called by OSGi-DS.
	 *
	 * @param modelSet
	 */
	public void unsetModelSet(IModelSet modelSet) {
		// FIXME: do handle the dynamic nature properly
		this.modelSet = null;
	}

	/**
	 * Binds the PluginConfigModel. Called by OSGi-DS.
	 *
	 * @param configModel
	 */
	protected void setPluginConfigModel(PluginConfigModel configModel) {
		configModel.begin();
		try {
			IResource cacheCfg = configModel.getManager().find(PLUGIN_CONFIG_URI.appendLocalPart("cache"), IResource.class);
			Object modelCfgSetting = cacheCfg.getSingle(PLUGIN_CONFIG_URI.appendLocalPart("model"));
			if (null != modelCfgSetting) {
				URI cacheModel = URIs.createURI(modelCfgSetting.toString());
				logger.info("using cacheModel=" + cacheModel);
				// TODO: actually set up internal cache model using that
				// configuration
			}
			Object extraRepositorySetting = cacheCfg.getSingle(PLUGIN_CONFIG_URI.appendLocalPart("extraRepository"));
			if (null != extraRepositorySetting) {
				useExtraRepository = Boolean.parseBoolean(extraRepositorySetting.toString());
			}
		} finally {
			configModel.end();
		}
	}

	/**
	 * Unbinds the PluginConfigModel. Called by OSGi-DS.
	 *
	 * @param configModel
	 */
	public void unsetPluginConfigModel(PluginConfigModel configModel) {
	}

	// FIXME: invocation order, called from FederationModelSetSupport
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

	public LdpCacheConnection getConnection() {
		return new LdpCacheConnection();
	}

	public RepositoryConnection getRepositoryConnection() {
		// FIXME: support ModelSet as internal repository
		if (!useExtraRepository) {
			throw new RepositoryException("Invalid configuration, no internal repository!");
		}
		return repository.getConnection();
	}

	/**
	 * Abstract away the difference between internal repository (for e.g.
	 * federation) and use with IModelSet.
	 * <p>
	 * FIXME: Get rid of this, work w/ KOMMA in LdpClient?
	 */
	public class LdpCacheConnection {

		protected RepositoryConnection conn;

		public LdpCacheConnection() {
			if (useExtraRepository) {
				conn = repository.getConnection();
				logger.trace("ctor() conn=" + conn);
			} else {
				modelSet.getUnitOfWork().begin();
			}
		}

		public void close() {
			if (useExtraRepository) {
				logger.trace("close() conn=" + conn);
				conn.close();
				conn = null;
			} else {
				modelSet.getUnitOfWork().end();
			}
		}

		public boolean isActive() {
			if (useExtraRepository) {
				return conn.isActive();
			} else {
				return cacheModel.getManager().getTransaction().isActive();
			}
		}

		public void begin() {
			if (useExtraRepository) {
				conn.begin();
			} else {
				cacheModel.getManager().getTransaction().begin();
			}
		}

		public void rollback() {
			logger.trace("");
			if (useExtraRepository) {
				conn.rollback();
			} else {
				cacheModel.getManager().getTransaction().rollback();
			}
		}

		public void commit() {
			logger.trace("commit() conn=" + conn);
			if (useExtraRepository) {
				conn.commit();
			} else {
				cacheModel.getManager().getTransaction().commit();
			}
		}

		public List<org.eclipse.rdf4j.model.Statement> getStatements(Resource subject, IRI predicate, Value object,
		                                                             boolean includeInferred, Resource... ctxs) {
			logger.trace("getStatements({}, {}, {}, {}, {})", subject, predicate, object, includeInferred, ctxs);
			if (useExtraRepository) {
				return Iterations.asList(conn.getStatements(subject, predicate, object, includeInferred, ctxs));
			} else {
				return cacheModel.getManager()
						.match(vc.fromRdf4j(subject), vc.fromRdf4j(predicate), vc.fromRdf4j(object))
						.mapWith(new IMap<IStatement, org.eclipse.rdf4j.model.Statement>() {
							@Override
							public org.eclipse.rdf4j.model.Statement map(IStatement stmt) {
								return vc.toRdf4j(stmt);
							}
						}).toList();
			}
		}

		public boolean add(org.eclipse.rdf4j.model.Statement statement, IRI ctx) {
			logger.trace("add({}, {})", statement, ctx);
			if (useExtraRepository) {
				conn.add(statement, ctx);
			} else {
				cacheModel.getManager()
						.add(new Statement( //
								vc.fromRdf4j(statement.getSubject()), vc.fromRdf4j(statement.getPredicate()),
								vc.fromRdf4j(statement.getObject()), vc.fromRdf4j(statement.getContext())));
			}
			return true;
		}

		public boolean remove(Resource subject, IRI predicate, Value object, Resource... ctxs) {
			logger.trace("remove({}, {}, {}, {})", subject, predicate, object, ctxs);
			if (useExtraRepository) {
				conn.remove(subject, predicate, object, ctxs);
			} else {
				for (Resource ctx : ctxs) {
					cacheModel.getManager().remove(new StatementPattern( //
							vc.fromRdf4j(subject), vc.fromRdf4j(predicate), vc.fromRdf4j(object), vc.fromRdf4j(ctx)));
				}
			}
			return true;
		}
	}
}
