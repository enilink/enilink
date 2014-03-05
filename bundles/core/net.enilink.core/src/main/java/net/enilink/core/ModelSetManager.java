package net.enilink.core;

import java.security.PrivilegedAction;
import java.util.Locale;

import javax.security.auth.Subject;

import net.enilink.auth.AuthModule;
import net.enilink.composition.properties.PropertySetFactory;
import net.enilink.core.security.ISecureEntity;
import net.enilink.core.security.SecureEntitySupport;
import net.enilink.core.security.SecureModelSetSupport;
import net.enilink.core.security.SecurePropertySetFactory;
import net.enilink.core.security.SecurityUtil;
import net.enilink.komma.core.IEntityManager;
import net.enilink.komma.core.IGraph;
import net.enilink.komma.core.IProvider;
import net.enilink.komma.core.IUnitOfWork;
import net.enilink.komma.core.KommaModule;
import net.enilink.komma.core.LinkedHashGraph;
import net.enilink.komma.core.StatementPattern;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;
import net.enilink.komma.em.util.UnitOfWork;
import net.enilink.komma.model.IModel;
import net.enilink.komma.model.IModelSet;
import net.enilink.komma.model.IModelSetFactory;
import net.enilink.komma.model.MODELS;
import net.enilink.komma.model.ModelPlugin;
import net.enilink.komma.model.ModelSetModule;
import net.enilink.komma.model.base.IURIMapRule;
import net.enilink.komma.model.base.SimpleURIMapRule;
import net.enilink.komma.workbench.IProjectModelSet;
import net.enilink.komma.workbench.ProjectModelSetSupport;
import net.enilink.vocab.acl.ACL;
import net.enilink.vocab.acl.Authorization;
import net.enilink.vocab.foaf.Agent;
import net.enilink.vocab.foaf.FOAF;
import net.enilink.vocab.rdf.RDF;
import net.enilink.vocab.rdfs.RDFS;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.util.Modules;

class ModelSetManager {
	public static final ModelSetManager INSTANCE = new ModelSetManager();
	public static final String REPOSITORY_TYPE;
	static {
		String repoType = System.getProperty("net.enilink.repository.type");
		if (repoType == null) {
			repoType = "owlim";
		}
		REPOSITORY_TYPE = repoType.toLowerCase();
	}

	static IContextProvider contextProvider = new IContextProvider() {
		@Override
		public IContext get() {
			try {
				// get the first valid context from any
				// registered session provider service
				BundleContext bundleContext = Activator.getContext();
				for (ServiceReference<IContextProvider> spRef : bundleContext
						.getServiceReferences(IContextProvider.class, null)) {
					IContext userCtx = bundleContext.getService(spRef).get();
					bundleContext.ungetService(spRef);
					if (userCtx != null) {
						return userCtx;
					}
				}
			} catch (InvalidSyntaxException ise) {
				// ignore
			}
			return null;
		}
	};

	static class ContextProviderModule extends AbstractModule {
		@Override
		protected void configure() {
			bind(IContextProvider.class).toInstance(contextProvider);
		}
	}

	private UnitOfWork uow = new UnitOfWork();
	private IModelSet modelSet;

	private ModelSetManager() {
	}

	protected void addServerInfo(URI modelSet, IGraph config) {
		String serverUrl = System.getProperty("net.enilink.repository.server");
		if (serverUrl == null) {
			// serverUrl = "http://localhost:10035"; // Allegrograph
			// serverUrl = "jdbc:virtuoso://localhost:1111"; // Virtuoso
			serverUrl = "http://localhost:8080/openrdf-sesame";
		}
		config.add(modelSet, MODELS.NAMESPACE_URI.appendLocalPart("server"),
				URIs.createURI(serverUrl));
		String username = System.getProperty("net.enilink.repository.username");
		if (username != null) {
			config.add(modelSet,
					MODELS.NAMESPACE_URI.appendLocalPart("username"), username);
			String password = System
					.getProperty("net.enilink.repository.password");
			if (password != null) {
				config.add(modelSet,
						MODELS.NAMESPACE_URI.appendLocalPart("password"),
						password);
			}
		}
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
						IContext context = contextProvider.get();
						return context != null ? context.getLocale() : Locale
								.getDefault();
					}
				};
			}

			@Override
			protected Class<? extends PropertySetFactory> getPropertySetFactoryClass() {
				return SecurePropertySetFactory.class;
			}
		}).with(new AbstractModule() {
			@Override
			protected void configure() {
				bind(UnitOfWork.class).toInstance(uow);
				bind(IUnitOfWork.class).toInstance(uow);
			}
		});
	}

	protected KommaModule createDataModelSetModule() {
		KommaModule module = ModelPlugin.createModelSetModule(getClass()
				.getClassLoader());
		module.addBehaviour(SessionModelSetSupport.class);
		module.addBehaviour(LazyModelSupport.class);

		if ("owlim".equals(REPOSITORY_TYPE)) {
			module.addBehaviour(OwlimDialectSupport.class);
		}

		module.addConcept(IProjectModelSet.class);
		module.addBehaviour(ProjectModelSetSupport.class);

		module.addBehaviour(SecureModelSetSupport.class);
		module.addConcept(ISecureEntity.class);
		module.addBehaviour(SecureEntitySupport.class);
		return module;
	}

	protected IGraph createModelSetConfig(URI ms) {
		IGraph graph = new LinkedHashGraph();
		graph.add(ms, RDF.PROPERTY_TYPE, MODELS.TYPE_MODELSET);

		// store meta data in repository
		// graph.add(ms, MODELS.PROPERTY_METADATACONTEXT,
		// URIs.createURI("komma:metadata"));
		return graph;
	}

	protected URI getPersistentModelSetType() {
		String localName = "RemoteModelSet";
		if (REPOSITORY_TYPE.equals("agraph")) {
			localName = "AGraphModelSet";
		}
		return MODELS.NAMESPACE_URI.appendLocalPart(localName);
	}

	protected IModelSet createMetaModelSet() {
		KommaModule module = ModelPlugin.createModelSetModule(getClass()
				.getClassLoader());
		module.addBehaviour(SessionModelSetSupport.class);
		module.addConcept(ISecureEntity.class);
		module.addBehaviour(SecureEntitySupport.class);

		Injector injector = Guice.createInjector(
				createModelSetGuiceModule(module), new ContextProviderModule());
		URI msUri = URIs.createURI("urn:enilink:metamodelset");
		IGraph graph = createModelSetConfig(msUri);
		addServerInfo(msUri, graph);
		graph.add(msUri, MODELS.NAMESPACE_URI.appendLocalPart("repository"),
				"enilink-meta");

		IModelSetFactory factory = injector.getInstance(IModelSetFactory.class);
		IModelSet metaModelSet = factory.createModelSet(graph,
				getPersistentModelSetType());

		// include model behaviors into meta model set
		metaModelSet.getModule().includeModule(createDataModelSetModule());
		return metaModelSet;
	}

	protected IModelSet createModelSet(IModel metaDataModel) {
		URI msUri = URIs.createURI("urn:enilink:modelset");

		IGraph graph = createModelSetConfig(msUri);
		addServerInfo(msUri, graph);
		graph.add(msUri, MODELS.NAMESPACE_URI.appendLocalPart("repository"),
				"enilink");
		metaDataModel.getManager().remove(
				new StatementPattern(msUri, MODELS.NAMESPACE_URI
						.appendLocalPart("server"), null));

		metaDataModel.getManager().add(graph);
		IModelSet.Internal modelSet = (IModelSet.Internal) metaDataModel
				.getManager().createNamed(msUri, MODELS.TYPE_MODELSET,
						getPersistentModelSetType());
		modelSet.create();
		return modelSet;
	}

	protected IModelSet createModelSet() {
		KommaModule module = createDataModelSetModule();
		module.includeModule(new AuthModule());

		Injector injector = Guice.createInjector(
				createModelSetGuiceModule(module), new ContextProviderModule());
		IModelSetFactory factory = injector.getInstance(IModelSetFactory.class);

		URI msUri = URIs.createURI("urn:enilink:modelset");
		IGraph graph = createModelSetConfig(msUri);
		graph.add(msUri, MODELS.NAMESPACE_URI.appendFragment("inference"),
				false);
		IModelSet modelSet = factory.createModelSet(graph,
				MODELS.NAMESPACE_URI.appendLocalPart(//
						// "OwlimModelSet" //
						"MemoryModelSet" //
						// "VirtuosoModelSet" //
						// "AGraphModelSet" //
						// "RemoteModelSet" //
						),
				MODELS.NAMESPACE_URI.appendLocalPart("ProjectModelSet"));

		return modelSet;
	}

	protected void createModels(final IModelSet modelSet) {
		if (modelSet instanceof IProjectModelSet) {
			IProject project = ResourcesPlugin.getWorkspace().getRoot()
					.getProject("models");
			System.out
					.println("Looking for models in: "
							+ ResourcesPlugin.getWorkspace().getRoot()
									.getLocationURI());
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
							boolean isMemoryRepo = "memory"
									.equals(REPOSITORY_TYPE);
							if (isMemoryRepo) {
								modelSet = createModelSet();
							} else {
								IModelSet metaModelSet = createMetaModelSet();
								IModel metaDataModel = metaModelSet.createModel(URIs
										.createURI("urn:enilink:metadata"));
								modelSet = createModelSet(metaDataModel);
							}
							if (isMemoryRepo
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
												ACL.TYPE_READ,
												net.enilink.vocab.rdfs.Class.class));
								auth.getAclMode()
										.add(em.find(
												ACL.TYPE_WRITE,
												net.enilink.vocab.rdfs.Class.class));
								auth.getAclMode()
										.add(em.find(
												ACL.TYPE_CONTROL,
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
