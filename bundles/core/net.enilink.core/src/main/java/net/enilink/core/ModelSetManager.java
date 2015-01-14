package net.enilink.core;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import javax.security.auth.Subject;

import net.enilink.auth.AuthModule;
import net.enilink.commons.iterator.IMap;
import net.enilink.commons.iterator.WrappedIterator;
import net.enilink.composition.properties.PropertySetFactory;
import net.enilink.core.security.ISecureEntity;
import net.enilink.core.security.SecureEntitySupport;
import net.enilink.core.security.SecureModelSetSupport;
import net.enilink.core.security.SecureModelSupport;
import net.enilink.core.security.SecurePropertySetFactory;
import net.enilink.core.security.SecurityUtil;
import net.enilink.komma.core.BlankNode;
import net.enilink.komma.core.IEntityManager;
import net.enilink.komma.core.IGraph;
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
import net.enilink.komma.core.visitor.IDataVisitor;
import net.enilink.komma.em.CacheModule;
import net.enilink.komma.em.CachingEntityManagerModule;
import net.enilink.komma.em.util.UnitOfWork;
import net.enilink.komma.model.IModel;
import net.enilink.komma.model.IModelSet;
import net.enilink.komma.model.IModelSetFactory;
import net.enilink.komma.model.IURIConverter;
import net.enilink.komma.model.MODELS;
import net.enilink.komma.model.ModelPlugin;
import net.enilink.komma.model.ModelSetModule;
import net.enilink.komma.model.ModelUtil;
import net.enilink.komma.model.base.ExtensibleURIConverter;
import net.enilink.komma.model.base.IURIMapRule;
import net.enilink.komma.model.base.SimpleURIMapRule;
import net.enilink.komma.workbench.IProjectModelSet;
import net.enilink.komma.workbench.ProjectModelSetSupport;
import net.enilink.vocab.acl.Authorization;
import net.enilink.vocab.acl.ENILINKACL;
import net.enilink.vocab.acl.WEBACL;
import net.enilink.vocab.foaf.Agent;
import net.enilink.vocab.foaf.FOAF;
import net.enilink.vocab.rdf.RDF;
import net.enilink.vocab.rdfs.RDFS;
import net.enilink.vocab.rdfs.Resource;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.name.Named;
import com.google.inject.util.Modules;

class ModelSetManager {
	private static final Logger log = LoggerFactory
			.getLogger(ModelSetManager.class);

	public static final ModelSetManager INSTANCE = new ModelSetManager();
	public static final IGraph config;
	static {
		final IGraph[] graph = { null };
		String configName = System.getProperty("net.enilink.config");
		if (configName != null) {
			URI configUri = null;
			try {
				configUri = URIs.createURI(configName);
			} catch (IllegalArgumentException iae) {

			}
			if (configUri == null && Files.exists(Paths.get(configName))) {
				configUri = URIs.createFileURI(configName);
			}
			if (configUri != null) {
				graph[0] = new LinkedHashGraph();
				try {
					IURIConverter uriConverter = new ExtensibleURIConverter();
					try (InputStream in = new BufferedInputStream(
							uriConverter.createInputStream(configUri))) {
						ModelUtil.readData(
								in,
								configUri.toString(),
								(String) uriConverter.contentDescription(
										configUri, null).get(
										IURIConverter.ATTRIBUTE_MIME_TYPE),
								new IDataVisitor<Void>() {
									@Override
									public Void visitBegin() {
										return null;
									}

									@Override
									public Void visitEnd() {
										return null;
									}

									@Override
									public Void visitStatement(IStatement stmt) {
										graph[0].add(stmt);
										return null;
									}
								});
					}
				} catch (Exception e) {
					log.error("Unable to read config file", e);
				}
			}
		}
		config = graph[0];
	}

	private static final URI META_MODELSET = URIs
			.createURI("urn:enilink:metadata");
	private static final URI DATA_MODELSET = URIs.createURI("urn:enilink:data");

	private UnitOfWork uow = new UnitOfWork();
	private IModelSet modelSet;

	protected void overwriteProperty(IGraph data, URI s, URI property,
			Object value) {
		if (value != null) {
			data.remove(s, property, null);
			data.add(s, property, value);
		}
	}

	protected void addSystemProperties(URI modelSet, IGraph config) {
		String target = modelSet.localPart();
		overwriteProperty(config, modelSet,
				MODELS.NAMESPACE_URI.appendLocalPart("server"),
				System.getProperty("net.enilink." + target + ".server"));
		overwriteProperty(config, modelSet,
				MODELS.NAMESPACE_URI.appendLocalPart("username"),
				System.getProperty("net.enilink." + target + ".username"));
		overwriteProperty(config, modelSet,
				MODELS.NAMESPACE_URI.appendLocalPart("password"),
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
						IContext context = ContextProviderModule.contextProvider
								.get();
						return context != null ? context.getLocale() : Locale
								.getDefault();
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
			protected List<? extends Module> createFactoryModules(
					KommaModule kommaModule) {
				List<Module> modules = new ArrayList<>(super
						.createFactoryModules(kommaModule));
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
		KommaModule module = ModelPlugin.createModelSetModule(getClass()
				.getClassLoader());
		module.addBehaviour(OwlimSeModelSetSupport.class);
		module.addBehaviour(SessionModelSetSupport.class);
		module.addBehaviour(LazyModelSupport.class);

		module.addConcept(IProjectModelSet.class);
		module.addBehaviour(ProjectModelSetSupport.class);

		module.addBehaviour(SecureModelSetSupport.class);
		module.addBehaviour(SecureModelSupport.class);
		module.addConcept(ISecureEntity.class);
		module.addBehaviour(SecureEntitySupport.class);
		return module;
	}

	protected IGraph createConfig(URI modelSet) {
		IGraph graph = new LinkedHashGraph();
		graph.add(modelSet, RDF.PROPERTY_TYPE, MODELS.TYPE_MODELSET);

		if (config != null) {
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
		}
		addSystemProperties(modelSet, graph);
		return graph;
	}

	protected IModelSet createMetaModelSet() {
		KommaModule module = ModelPlugin.createModelSetModule(getClass()
				.getClassLoader());
		module.addBehaviour(OwlimSeModelSetSupport.class);
		module.addBehaviour(SessionModelSetSupport.class);
		module.addConcept(ISecureEntity.class);
		module.addBehaviour(SecureEntitySupport.class);

		Injector injector = Guice.createInjector(
				createModelSetGuiceModule(module), new ContextProviderModule());
		URI msUri = META_MODELSET;
		IGraph graph = createConfig(msUri);
		if (!graph.contains(msUri,
				MODELS.NAMESPACE_URI.appendLocalPart("repository"), null)) {
			graph.add(msUri,
					MODELS.NAMESPACE_URI.appendLocalPart("repository"),
					"enilink-meta");
		}

		IModelSetFactory factory = injector.getInstance(IModelSetFactory.class);
		IModelSet metaModelSet = factory.createModelSet(msUri, graph);

		// include model behaviors into meta model set
		metaModelSet.getModule().includeModule(createDataModelSetModule());
		return metaModelSet;
	}

	protected IModelSet createModelSet(IModel metaDataModel) {
		URI msUri = DATA_MODELSET;

		IGraph graph = createConfig(msUri);
		// remove old config
		metaDataModel.getManager().remove(
				WrappedIterator.create(
						graph.filter(msUri, null, null).iterator()).mapWith(
						new IMap<IStatement, IStatement>() {
							@Override
							public IStatement map(IStatement stmt) {
								return new Statement(stmt.getSubject(), stmt
										.getPredicate(), null);
							}
						}));
		metaDataModel.getManager().add(graph);

		// maybe use toInstance here so that config data has not to be inserted
		// into the database
		IModelSet.Internal modelSet = (IModelSet.Internal) metaDataModel
				.getManager().find(msUri);
		modelSet = modelSet.create();
		return modelSet;
	}

	protected IModelSet createModelSet() {
		KommaModule module = createDataModelSetModule();
		module.includeModule(new AuthModule());

		Injector injector = Guice.createInjector(
				createModelSetGuiceModule(module), new ContextProviderModule());
		IModelSetFactory factory = injector.getInstance(IModelSetFactory.class);

		URI msUri = DATA_MODELSET;
		IGraph graph = createConfig(msUri);
		if (config == null) {
			// config file was not specified
			graph.add(msUri, MODELS.NAMESPACE_URI.appendFragment("inference"),
					false);
			graph.add(msUri, RDF.PROPERTY_TYPE,
					MODELS.NAMESPACE_URI.appendLocalPart(//
							// "OwlimModelSet" //
							"MemoryModelSet" //
							// "VirtuosoModelSet" //
							// "AGraphModelSet" //
							// "RemoteModelSet" //
							));
		}
		graph.add(msUri, RDF.PROPERTY_TYPE,
				MODELS.NAMESPACE_URI.appendLocalPart("ProjectModelSet"));
		IModelSet modelSet = factory.createModelSet(msUri, graph);
		return modelSet;
	}

	protected void createModels(final IModelSet modelSet) {
		// create default users model
		modelSet.createModel(SecurityUtil.USERS_MODEL);
		// set ACL mode "RESTRICTED" for users model
		IEntityManager em = modelSet.getMetaDataManager();
		Authorization auth = em.createNamed(
				URIs.createURI("urn:auth:usersModelRestricted"),
				Authorization.class);
		auth.setAclAccessTo(em.find(SecurityUtil.USERS_MODEL, Resource.class));
		auth.setAclAgentClass(em.find(FOAF.TYPE_AGENT,
				net.enilink.vocab.rdfs.Class.class));
		auth.getAclMode().clear();
		auth.getAclMode().add(
				em.find(ENILINKACL.MODE_WRITERESTRICTED,
						net.enilink.vocab.rdfs.Class.class));

		// add application specific models from the workspace
		if (modelSet instanceof IProjectModelSet) {
			IProject project = ResourcesPlugin.getWorkspace().getRoot()
					.getProject("models");
			System.out.println("Looking for models in: "
					+ project.getLocation());
			try {
				if (!project.exists()) {
					project.create(null);
				}
				project.open(null);

				((IProjectModelSet) modelSet).setProject(project);
				for (IURIMapRule rule : modelSet.getURIConverter()
						.getURIMapRules()) {
					if (rule instanceof SimpleURIMapRule) {
						String modelUri = ((SimpleURIMapRule) rule)
								.getPattern();
						modelSet.createModel(URIs.createURI(modelUri));
					}
				}
			} catch (Exception e) {
				System.err.println(e.getMessage());
			}
		}
	}

	public synchronized IUnitOfWork getUnitOfWork() {
		return uow;
	}

	public synchronized IModelSet getModelSet() {
		if (modelSet == null) {
			Subject.doAs(SecurityUtil.SYSTEM_USER_SUBJECT,
					new PrivilegedAction<Object>() {
						@Override
						public Object run() {

							if (config == null
									|| !config.contains(META_MODELSET, null,
											null)) {
								// create only a data modelset
								modelSet = createModelSet();
							} else {
								IModelSet metaModelSet = createMetaModelSet();
								IModel metaDataModel = metaModelSet.createModel(URIs
										.createURI("urn:enilink:metadata"));
								modelSet = createModelSet(metaDataModel);
							}
							// register globally readable models
							modelSet.getModule().addReadableGraph(
									SecurityUtil.USERS_MODEL);
							// register some namespace
							modelSet.getModule().addNamespace("foaf",
									FOAF.NAMESPACE_URI);
							if (config == null
									|| "all".equals(System
											.getProperty("net.enilink.acl.anonymous"))) {
								IEntityManager em = modelSet
										.getMetaDataManager();
								Authorization auth = em.createNamed(
										URIs.createURI("urn:auth:anonymousAll"),
										Authorization.class);
								auth.setAclAccessToClass(em.find(
										MODELS.TYPE_MODEL,
										net.enilink.vocab.rdfs.Class.class));
								auth.setAclAgent(em.find(
										SecurityUtil.UNKNOWN_USER, Agent.class));
								auth.getAclMode()
										.add(em.find(
												WEBACL.MODE_READ,
												net.enilink.vocab.rdfs.Class.class));
								auth.getAclMode()
										.add(em.find(
												WEBACL.MODE_WRITE,
												net.enilink.vocab.rdfs.Class.class));
								auth.getAclMode()
										.add(em.find(
												WEBACL.MODE_CONTROL,
												net.enilink.vocab.rdfs.Class.class));
							}
							modelSet.getMetaDataManager().createNamed(
									FOAF.TYPE_AGENT, RDFS.TYPE_CLASS);
							modelSet.getMetaDataManager().createNamed(
									SecurityUtil.UNKNOWN_USER, FOAF.TYPE_AGENT);
							createModels(modelSet);
							return null;
						}
					});
		}
		return modelSet;
	}

	public synchronized void shutdown() {
		if (modelSet != null) {
			modelSet.dispose();
			modelSet = null;
		}
	}
}