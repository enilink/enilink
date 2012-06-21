package net.enilink.core;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import com.google.inject.Guice;
import com.google.inject.Injector;

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
import net.enilink.komma.core.IGraph;
import net.enilink.komma.core.IUnitOfWork;
import net.enilink.komma.core.KommaModule;
import net.enilink.komma.core.LinkedHashGraph;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIImpl;
import net.enilink.komma.workbench.IProjectModelSet;
import net.enilink.komma.workbench.ProjectModelSetSupport;

public class ModelSetManager {
	public static final ModelSetManager INSTANCE = new ModelSetManager();

	private Injector injector;
	private IModelSet modelSet;

	private ModelSetManager() {
		KommaModule module = ModelCore.createModelSetModule(getClass()
				.getClassLoader());
		module.addBehaviour(SessionModelSetSupport.class);
		module.addBehaviour(LazyModelSupport.class);

		module.addConcept(IProjectModelSet.class);
		module.addBehaviour(ProjectModelSetSupport.class);

		injector = Guice.createInjector(new ModelSetModule(module));
	}

	protected IModelSet createModelSet() {
		IModelSetFactory factory = injector.getInstance(IModelSetFactory.class);

		IGraph graph = new LinkedHashGraph();
		URI msUri = URIImpl.createURI("urn:virtusoso:modelset");
		graph.add(msUri, RDF.PROPERTY_TYPE, MODELS.TYPE_MODELSET);
		graph.add(msUri, MODELS.NAMESPACE_URI.appendLocalPart("metaDataModel"),
				URIImpl.createURI("urn:komma:metaData"));
		graph.add(msUri, MODELS.NAMESPACE_URI.appendLocalPart("host"), //
				"localhost" //
		);
		graph.add(msUri, MODELS.NAMESPACE_URI.appendLocalPart("port"), //
				// 1111 // Virtuoso
				10035 // Allegrograph
		);
		// store meta data in Virtuoso repository
		// graph.add(msUri, MODELS.PROPERTY_METADATACONTEXT,
		// URIImpl.createURI("urn:komma:metadata"));

		IModelSet modelSet = factory.createModelSet(graph,
				MODELS.NAMESPACE_URI.appendLocalPart(//
						"OwlimModelSet" //
						// "VirtuosoModelSet" //
//						 "AGraphModelSet"
						),
				URIImpl.createURI(MODELS.NAMESPACE + "ProjectModelSet"));

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
		return injector.getInstance(IUnitOfWork.class);
	}

	public synchronized IModelSet getModelSet() {
		if (modelSet == null) {
			modelSet = createModelSet();
			createModels(modelSet);
		}
		return modelSet;
	}
}
