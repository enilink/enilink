package net.enilink.platform.core;

import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import javax.security.auth.Subject;

import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.name.Named;
import com.google.inject.util.Modules;

import net.enilink.commons.iterator.IMap;
import net.enilink.commons.iterator.WrappedIterator;
import net.enilink.composition.properties.PropertySetFactory;
import net.enilink.komma.core.BlankNode;
import net.enilink.komma.core.IEntityManager;
import net.enilink.komma.core.IGraph;
import net.enilink.komma.core.ILiteral;
import net.enilink.komma.core.IProvider;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.IStatement;
import net.enilink.komma.core.IUnitOfWork;
import net.enilink.komma.core.KommaModule;
import net.enilink.komma.core.LinkedHashGraph;
import net.enilink.komma.core.Properties;
import net.enilink.komma.core.Statement;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;
import net.enilink.komma.em.CacheModule;
import net.enilink.komma.em.CachingEntityManagerModule;
import net.enilink.komma.em.util.UnitOfWork;
import net.enilink.komma.model.IModel;
import net.enilink.komma.model.IModelSet;
import net.enilink.komma.model.IModelSetFactory;
import net.enilink.komma.model.MODELS;
import net.enilink.komma.model.ModelPlugin;
import net.enilink.komma.model.ModelSetModule;
import net.enilink.komma.model.ModelUtil;
import net.enilink.komma.model.base.IURIMapRule;
import net.enilink.komma.model.base.IURIMapRuleSet;
import net.enilink.komma.model.base.SimpleURIMapRule;
import net.enilink.platform.core.security.ISecureEntity;
import net.enilink.platform.core.security.SecureEntitySupport;
import net.enilink.platform.core.security.SecureModelSetSupport;
import net.enilink.platform.core.security.SecureModelSupport;
import net.enilink.platform.core.security.SecurePropertySetFactory;
import net.enilink.platform.core.security.SecurityUtil;
import net.enilink.platform.security.auth.AccountHelper;
import net.enilink.platform.security.auth.AuthModule;
import net.enilink.vocab.acl.Authorization;
import net.enilink.vocab.acl.ENILINKACL;
import net.enilink.vocab.acl.WEBACL;
import net.enilink.vocab.auth.AUTH;
import net.enilink.vocab.foaf.FOAF;
import net.enilink.vocab.rdf.RDF;
import net.enilink.vocab.rdfs.RDFS;
import net.enilink.vocab.rdfs.Resource;

class ModelSetManager {
	private static final Logger log = LoggerFactory.getLogger(ModelSetManager.class);

	public static final ModelSetManager INSTANCE = new ModelSetManager();

	private static final URI META_MODELSET = URIs.createURI("urn:enilink:metadata");
	private static final URI DATA_MODELSET = URIs.createURI("urn:enilink:data");

	private UnitOfWork uow = new UnitOfWork();
	private IModelSet modelSet;

	protected void overwriteProperty(IGraph data, URI s, URI property, Object value) {
		if (value != null) {
			data.remove(s, property, null);
			data.add(s, property, value);
		}
	}

	protected void addSystemProperties(URI modelSet, IGraph config) {
		String target = modelSet.localPart();
		overwriteProperty(config, modelSet, MODELS.NAMESPACE_URI.appendLocalPart("server"),
				System.getProperty("net.enilink." + target + ".server"));
		overwriteProperty(config, modelSet, MODELS.NAMESPACE_URI.appendLocalPart("username"),
				System.getProperty("net.enilink." + target + ".username"));
		overwriteProperty(config, modelSet, MODELS.NAMESPACE_URI.appendLocalPart("password"),
				System.getProperty("net.enilink." + target + ".password"));
	}

	protected Module createModelSetGuiceModule(KommaModule module) {
		return Modules.override(new ModelSetModule(module) {
			@Override
			protected IProvider<Locale> getLocaleProvider() {
				return new IProvider<Locale>() {
					@Override
					public Locale get() {
						// return locale according to current context (RAP or
						// Lift)
						IContext context = ContextProviderModule.contextProvider.get();
						return context != null ? context.getLocale() : Locale.getDefault();
					}
				};
			}

			@Override
			protected Module getEntityManagerModule() {
				return new CachingEntityManagerModule() {
					@Override
					protected Class<? extends PropertySetFactory> getPropertySetFactoryClass() {
						return SecurePropertySetFactory.class;
					}
				};
			}

			@Override
			protected List<? extends Module> createFactoryModules(KommaModule kommaModule) {
				List<Module> modules = new ArrayList<>(super.createFactoryModules(kommaModule));
				modules.add(new CacheModule(BlankNode.generateId()));
				return modules;
			}
		}).with(new AbstractModule() {
			@Override
			protected void configure() {
				bind(UnitOfWork.class).toInstance(uow);
				bind(IUnitOfWork.class).toInstance(uow);
			}

			@Provides
			@Named("net.enilink.komma.properties")
			Map<String, Object> provideProperties() {
				Map<String, Object> properties = new HashMap<>();
				properties.put(Properties.TIMEOUT, 20000);
				return properties;
			}
		});
	}

	protected KommaModule createDataModelSetModule() {
		KommaModule module = ModelPlugin.createModelSetModule(getClass().getClassLoader());
		module.addBehaviour(OwlimSeModelSetSupport.class);
		module.addBehaviour(SessionModelSetSupport.class);
		module.addBehaviour(LazyModelSupport.class);

		module.addBehaviour(SecureModelSetSupport.class);
		module.addBehaviour(SecureModelSupport.class);
		module.addConcept(ISecureEntity.class);
		module.addBehaviour(SecureEntitySupport.class);
		return module;
	}

	protected IGraph createModelSetConfig(Config config, URI modelSet) {
		IGraph graph = new LinkedHashGraph();
		graph.add(modelSet, RDF.PROPERTY_TYPE, MODELS.TYPE_MODELSET);

		// add data to config of current model set
		Set<IReference> seen = new HashSet<>();
		Queue<IReference> queue = new LinkedList<>();
		queue.add(modelSet);
		while (!queue.isEmpty()) {
			IReference s = queue.remove();
			if (seen.add(s)) {
				IGraph about = config.filter(s, null, null);
				graph.addAll(about);
				for (Object o : about.objects()) {
					if (o instanceof IReference && !seen.contains(o)) {
						queue.add((IReference) o);
					}
				}
			}
		}
		addSystemProperties(modelSet, graph);
		return graph;
	}

	protected IModelSet createMetaModelSet(Config config) {
		KommaModule module = ModelPlugin.createModelSetModule(getClass().getClassLoader());
		module.addBehaviour(SessionModelSetSupport.class);
		module.addConcept(ISecureEntity.class);
		module.addBehaviour(SecureEntitySupport.class);

		Injector injector = Guice.createInjector(createModelSetGuiceModule(module), new ContextProviderModule());
		URI msUri = META_MODELSET;
		IGraph graph = createModelSetConfig(config, msUri);
		if (!graph.contains(msUri, MODELS.NAMESPACE_URI.appendLocalPart("repository"), null)) {
			graph.add(msUri, MODELS.NAMESPACE_URI.appendLocalPart("repository"), "enilink-meta");
		}

		IModelSetFactory factory = injector.getInstance(IModelSetFactory.class);
		IModelSet metaModelSet = factory.createModelSet(msUri, graph);

		// include model behaviors into meta model set
		metaModelSet.getModule().includeModule(createDataModelSetModule());
		return metaModelSet;
	}

	protected IModelSet createModelSet(Config config, IModel metaDataModel) {
		URI msUri = DATA_MODELSET;

		IGraph graph = createModelSetConfig(config, msUri);
		// remove old config
		metaDataModel.getManager().remove(WrappedIterator.create(graph.filter(msUri, null, null).iterator())
				.mapWith(stmt -> new Statement(stmt.getSubject(), stmt.getPredicate(), null)));
		graph.add(msUri, RDF.PROPERTY_TYPE, MODELS.NAMESPACE_URI.appendLocalPart("ProjectModelSet"));
		metaDataModel.getManager().add(graph);

		// maybe use toInstance here so that config data has not to be inserted
		// into the database
		IModelSet.Internal modelSet = (IModelSet.Internal) metaDataModel.getManager().find(msUri);
		modelSet = modelSet.create();
		return modelSet;
	}

	protected IModelSet createModelSet(Config config) {
		URI msUri = DATA_MODELSET;
		boolean hasConfig = config.contains(msUri, null, null);
		IGraph graph = createModelSetConfig(config, msUri);
		if (!hasConfig) {
			// config file was not specified
			graph.add(msUri, MODELS.NAMESPACE_URI.appendFragment("inference"), false);
			graph.add(msUri, RDF.PROPERTY_TYPE, MODELS.NAMESPACE_URI.appendLocalPart(//
					"MemoryModelSet" //
			));
		}

		KommaModule module = createDataModelSetModule();
		module.includeModule(new AuthModule());

		// directly use meta data context for creating the model
		IReference metaDataContext = graph.filter(msUri, MODELS.PROPERTY_METADATACONTEXT, null).objectReference();
		if (metaDataContext != null && metaDataContext.getURI() != null) {
			module.addReadableGraph(metaDataContext.getURI());
			module.addWritableGraph(metaDataContext.getURI());
		}

		Injector injector = Guice.createInjector(createModelSetGuiceModule(module), new ContextProviderModule());
		IModelSetFactory factory = injector.getInstance(IModelSetFactory.class);

		IModelSet modelSet = factory.createModelSet(msUri, graph);
		return modelSet;
	}

	protected void createModels(final IModelSet modelSet) {
		// create default users model
		modelSet.createModel(SecurityUtil.USERS_MODEL);
		// set ACL mode "RESTRICTED" for users model
		IEntityManager em = modelSet.getMetaDataManager();
		Authorization auth = em.createNamed(URIs.createURI("urn:auth:usersModelRestricted"), Authorization.class);
		auth.setAclAccessTo(em.find(SecurityUtil.USERS_MODEL, Resource.class));
		auth.setAclAgentClass(em.find(FOAF.TYPE_AGENT, net.enilink.vocab.rdfs.Class.class));
		auth.getAclMode().clear();
		auth.getAclMode().add(em.find(ENILINKACL.MODE_WRITERESTRICTED, net.enilink.vocab.rdfs.Class.class));

		// add application specific models from the workspace
		String modelsLookupDir = FrameworkUtil.getBundle(getClass()).getBundleContext()
				.getProperty("net.enilink.models.dir");
		if (modelsLookupDir != null) {
			log.info("Looking for models in: {}", modelsLookupDir);
			try {
				PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:**/*.{ttl,owl}");
				List<Path> modelFiles = Files.walk(Paths.get(modelsLookupDir)).filter(matcher::matches)
						.collect(Collectors.toList());
				IURIMapRuleSet mapRules = modelSet.getURIConverter().getURIMapRules();
				for (Path modelFile : modelFiles) {
					URI fileUri = URIs.createFileURI(modelFile.toString());
					String modelUri;
					try (InputStream in = Files.newInputStream(modelFile)) {
						String mimeType = ModelUtil.mimeType(modelFile.toString());
						// use the embedded ontology element as model URI
						modelUri = ModelUtil.findOntology(in, fileUri.toString(), mimeType);
					}
					if (modelUri != null) {
						mapRules.addRule(new SimpleURIMapRule(modelUri, modelFile.toString()));
					} else {
						modelUri = modelFile.toString();
					}
					log.info("Creating model <{}>", modelUri);
					modelSet.createModel(URIs.createURI(modelUri));
					// if (loadModels) {
					// modelSet.getModel(URIs.createURI(modelUri), true);
					// }
				}
			} catch (Exception e) {
				log.error("Error while loading models", e);
			}
		}
	}

	public synchronized IUnitOfWork getUnitOfWork() {
		return uow;
	}

	public synchronized IModelSet getModelSet() {
		if (modelSet == null) {
			modelSet = new UseService<Config, IModelSet>(Config.class) {
				@Override
				protected IModelSet withService(final Config config) {
					return Subject.doAs(SecurityUtil.SYSTEM_USER_SUBJECT, new PrivilegedAction<IModelSet>() {
						@Override
						public IModelSet run() {
							IModelSet modelSet;
							if (!config.contains(META_MODELSET, null, null)) {
								// create only a data modelset
								modelSet = createModelSet(config);
							} else {
								IModelSet metaModelSet = createMetaModelSet(config);
								IModel metaDataModel = metaModelSet.createModel(URIs.createURI("urn:enilink:metadata"));
								modelSet = createModelSet(config, metaDataModel);
							}
							// register globally readable models
							modelSet.getModule().addReadableGraph(SecurityUtil.USERS_MODEL);
							// register foaf namespace
							modelSet.getModule().addNamespace("foaf", FOAF.NAMESPACE_URI);

							IEntityManager em = modelSet.getMetaDataManager();
							em.createNamed(FOAF.TYPE_AGENT, RDFS.TYPE_CLASS);
							em.createNamed(SecurityUtil.UNKNOWN_USER, FOAF.TYPE_AGENT);

							// load users, groups and ACL config
							loadUsersAndGroups(em, config);
							loadAcls(em, config);

							createModels(modelSet);
							return modelSet;
						}
					});
				}
			}.getResult();
			// close initial unit of work
			getUnitOfWork().end();
		}
		return modelSet;
	}

	protected void loadUsersAndGroups(IEntityManager em, Config config) {
		// seenAgents filters users and/or groups with multiple matching types
		Set<IReference> seenAgents = new HashSet<>();
		for (IReference rdfType : Arrays.asList(FOAF.TYPE_AGENT, FOAF.TYPE_PERSON)) {
			for (IReference agent : config.filter(null, RDF.PROPERTY_TYPE, rdfType).subjects()) {
				if (!seenAgents.add(agent)) {
					continue;
				}

				Set<IStatement> toAdd = new HashSet<>();
				IGraph about = config.filter(agent, null, null);
				for (IStatement stmt : about) {
					// encode given password
					if (AUTH.PROPERTY_PASSWORD.equals(stmt.getPredicate())) {
						if (stmt.getObject() instanceof ILiteral) {
							toAdd.add(new Statement(stmt.getSubject(), stmt.getPredicate(),
									AccountHelper.encodePassword(((ILiteral) stmt.getObject()).getLabel())));
						}
					} else {
						toAdd.add(stmt);
					}
				}

				// derive nick name from URI
				if (config.filter(agent, FOAF.PROPERTY_NICK, null).isEmpty()) {
					toAdd.add(new Statement(agent, FOAF.PROPERTY_NICK, agent.getURI().localPart()));
				}

				// add statements about agent
				em.add(toAdd);

				// add referenced objects
				Set<IReference> seen = new HashSet<>();
				for (IStatement stmt : toAdd) {
					if (stmt.getObject() instanceof IReference && seen.add((IReference) stmt.getObject())) {
						copyFromGraph(em, (IReference) stmt.getObject(), config);
					}
				}
			}
		}

		for (IReference rdfType : Arrays.asList(FOAF.TYPE_GROUP, FOAF.TYPE_ORGANIZATION)) {
			for (IReference group : config.filter(null, RDF.PROPERTY_TYPE, rdfType).subjects()) {
				if (!seenAgents.add(group)) {
					continue;
				}

				copyFromGraph(em, group, config);
			}
		}
	}

	protected void loadAcls(IEntityManager em, Config config) {
		for (IReference aclAuth : config.filter(null, RDF.PROPERTY_TYPE, WEBACL.TYPE_AUTHORIZATION).subjects()) {
			copyFromGraph(em, aclAuth, config);
		}
	}

	protected void copyFromGraph(IEntityManager em, IReference subject, IGraph graph) {
		Set<IReference> seen = new HashSet<>();
		Queue<IReference> queue = new LinkedList<>();
		queue.add(subject);
		while (!queue.isEmpty()) {
			IReference s = queue.remove();
			if (seen.add(s)) {
				IGraph about = graph.filter(s, null, null);
				em.add(about);
				for (Object o : about.objects()) {
					if (o instanceof IReference && !seen.contains(o)) {
						queue.add((IReference) o);
					}
				}
			}
		}
	}

	public synchronized void shutdown() {
		if (modelSet != null) {
			modelSet.dispose();
			modelSet = null;
		}
	}
}