package net.enilink.core;

import java.security.PrivilegedAction;

import javax.security.auth.Subject;

import net.enilink.auth.AuthModule;
import net.enilink.core.security.ISecureEntity;
import net.enilink.core.security.SecureEntitySupport;
import net.enilink.core.security.SecureModelSetSupport;
import net.enilink.core.security.SecurePropertySetFactory;
import net.enilink.core.security.SecurityUtil;
import net.enilink.vocab.acl.ACL;
import net.enilink.vocab.acl.Authorization;
import net.enilink.vocab.foaf.Agent;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import net.enilink.composition.properties.PropertySetFactory;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.util.Modules;

import net.enilink.vocab.rdf.RDF;
import net.enilink.komma.concepts.IResource;
import net.enilink.komma.model.IModel;
import net.enilink.komma.model.IModelSet;
import net.enilink.komma.model.IModelSetFactory;
import net.enilink.komma.model.MODELS;
import net.enilink.komma.model.ModelCore;
import net.enilink.komma.model.ModelSetModule;
import net.enilink.komma.model.base.IURIMapRule;
import net.enilink.komma.model.base.SimpleURIMapRule;
import net.enilink.komma.model.concepts.Model;
import net.enilink.komma.core.IEntityManager;
import net.enilink.komma.core.IGraph;
import net.enilink.komma.core.IUnitOfWork;
import net.enilink.komma.core.KommaModule;
import net.enilink.komma.core.LinkedHashGraph;
import net.enilink.komma.core.StatementPattern;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIImpl;
import net.enilink.komma.util.UnitOfWork;
import net.enilink.komma.workbench.IProjectModelSet;
import net.enilink.komma.workbench.ProjectModelSetSupport;

public class ModelSetManager {
	public static final ModelSetManager INSTANCE = new ModelSetManager();
	private static final String REPOSITORY_TYPE;
	static {
		String repoType = System.getProperty("net.enilink.repository.type");
		if (repoType == null) {
			repoType = "owlim";
		}
		REPOSITORY_TYPE = repoType.toLowerCase();
	}

	static class SessionProviderModule extends AbstractModule {
		@Override
		protected void configure() {
		}

		@Provides
		@Singleton
		protected ISessionProvider provideSessionProvider() {
			return new ISessionProvider() {
				@Override
				public ISession get() {
					try {
						// get the first valid session from any
						// registered session provider service
						BundleContext context = Activator.getContext();
						for (ServiceReference<ISessionProvider> spRef : context
								.getServiceReferences(ISessionProvider.class,
										null)) {
							ISession session = context.getService(spRef).get();
							context.ungetService(spRef);
							if (session != null) {
								return session;
							}
						}
					} catch (InvalidSyntaxException ise) {
						// ignore
					}
					return null;
				}
			};
		}
	}

	private UnitOfWork uow = new UnitOfWork();
	private IModelSet modelSet;

	private ModelSetManager() {
	}

	protected Module createModelSetGuiceModule(KommaModule module) {
		return Modules.override(new ModelSetModule(module) {
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
		KommaModule module = ModelCore.createModelSetModule(getClass()
				.getClassLoader());
		module.addBehaviour(SessionModelSetSupport.class);
		module.addBehaviour(LazyModelSupport.class);

		if ("owlim".endsWith(REPOSITORY_TYPE)) {
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
		// URIImpl.createURI("urn:komma:metadata"));
		return graph;
	}

	protected IModelSet createMetaModelSet() {
		KommaModule module = ModelCore.createModelSetModule(getClass()
				.getClassLoader());
		module.addBehaviour(SessionModelSetSupport.class);
		module.addConcept(ISecureEntity.class);
		module.addBehaviour(SecureEntitySupport.class);

		Injector injector = Guice.createInjector(
				createModelSetGuiceModule(module), new SessionProviderModule());
		URI msUri = URIImpl.createURI("urn:enilink:metamodelset");
		IGraph graph = createModelSetConfig(msUri);
		graph.add(msUri, MODELS.NAMESPACE_URI.appendLocalPart("server"),
		// URIImpl.createURI("http://localhost:10035") // Allegrograph
				URIImpl.createURI("http://localhost:8080/openrdf-sesame") // Sesame
		// URIImpl.createURI("jdbc:virtuoso://localhost:1111") // Virtuoso

		);
		graph.add(msUri, MODELS.NAMESPACE_URI.appendLocalPart("repository"),
				"enilink-meta");

		IModelSetFactory factory = injector.getInstance(IModelSetFactory.class);
		IModelSet metaModelSet = factory.createModelSet(graph,
				MODELS.NAMESPACE_URI.appendLocalPart("RemoteModelSet"));

		// include model behaviors into meta model set
		metaModelSet.getModule().includeModule(createDataModelSetModule());
		return metaModelSet;
	}

	protected IModelSet createModelSet(IModel metaDataModel) {
		URI msUri = URIImpl.createURI("urn:enilink:modelset");

		IGraph graph = createModelSetConfig(msUri);
		graph.add(msUri, MODELS.NAMESPACE_URI.appendLocalPart("server"),
		// URIImpl.createURI("http://localhost:10035") // Allegrograph
				URIImpl.createURI("http://localhost:8080/openrdf-sesame") // Sesame
		// URIImpl.createURI("jdbc:virtuoso://localhost:1111") // Virtuoso

		);
		graph.add(msUri, MODELS.NAMESPACE_URI.appendLocalPart("repository"),
				"enilink");
		metaDataModel.getManager().remove(
				new StatementPattern(msUri, MODELS.NAMESPACE_URI
						.appendLocalPart("server"), null));

		metaDataModel.getManager().add(graph);
		IModelSet.Internal modelSet = (IModelSet.Internal) metaDataModel
				.getManager().createNamed(msUri, MODELS.TYPE_MODELSET,
						MODELS.NAMESPACE_URI.appendLocalPart(//
								// "AGraphModelSet" //
								"RemoteModelSet" //
								));
		modelSet.create();
		return modelSet;
	}

	protected IModelSet createModelSet() {
		KommaModule module = createDataModelSetModule();
		module.includeModule(new AuthModule());

		Injector injector = Guice.createInjector(
				createModelSetGuiceModule(module), new SessionProviderModule());
		IModelSetFactory factory = injector.getInstance(IModelSetFactory.class);

		URI msUri = URIImpl.createURI("urn:enilink:modelset");
		IGraph graph = createModelSetConfig(msUri);
		IModelSet modelSet = factory.createModelSet(graph,
				MODELS.NAMESPACE_URI.appendLocalPart(//
						"OwlimModelSet" //
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
						modelSet.createModel(URIImpl.createURI(modelUri));
					}
				}
			} catch (Exception e) {
				System.err.println(e.getMessage());
			}
		}

		IModel structureModel = modelSet
				.createModel(URIImpl
						.createURI("http://enilink.net/vocab/innocat/structure"));
		((IResource) structureModel).setRdfsLabel("Struktur aus Analyse");

		IModel pdModel = modelSet
				.createModel(URIImpl
						.createURI("http://iwu.fraunhofer.de/data/pd/vw350_tuer_hinten_umbau"));
		((IResource) pdModel).setRdfsLabel("Struktur aus Process Designer");

		IModel measurementsModel = modelSet
				.createModel(URIImpl
						.createURI("http://iwu.fraunhofer.de/data/innocat/measurements"));
		((Model) measurementsModel).setModelLoaded(true);
		((IResource) measurementsModel).setRdfsLabel("Messdaten");
		measurementsModel.addImport(structureModel.getURI(), null);
		measurementsModel.addImport(
				URIImpl.createURI("http://enilink.net/vocab/measurements"),
				null);
		measurementsModel.getManager().setNamespace("structure",
				structureModel.getURI().appendFragment(""));

		modelSet.createModel(URIImpl
				.createURI("http://enilink.net/vocab/manufacturing"));

		// try {
		// model.load(new HashMap<Object, Object>());
		// } catch (IOException e) {
		// // TODO Auto-generated catch block
		// e.printStackTrace();
		// }
		// ((AdapterFactoryEditingDomain) getEditingDomainProvider()
		// .getEditingDomain()).getModelToReadOnlyMap().put(model, false);
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
							if ("memory".equals(REPOSITORY_TYPE)) {
								modelSet = createModelSet();
								IEntityManager em = modelSet
										.getMetaDataManager();
								Authorization auth = em
										.create(Authorization.class);
								auth.setAclAccessToClass(em
										.find(MODELS.TYPE_MODEL,
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
							} else {
								IModelSet metaModelSet = createMetaModelSet();
								IModel metaDataModel = metaModelSet.createModel(URIImpl
										.createURI("urn:enilink:metadata"));
								modelSet = createModelSet(metaDataModel);
							}
							createModels(modelSet);
							return null;
						}
					});
		}
		return modelSet;
	}
}
