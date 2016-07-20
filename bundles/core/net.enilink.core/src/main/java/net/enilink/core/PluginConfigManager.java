package net.enilink.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

import org.eclipse.osgi.service.datalocation.Location;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.util.Modules;

import net.enilink.komma.core.BlankNode;
import net.enilink.komma.core.IGraph;
import net.enilink.komma.core.IUnitOfWork;
import net.enilink.komma.core.KommaModule;
import net.enilink.komma.core.LinkedHashGraph;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;
import net.enilink.komma.em.CacheModule;
import net.enilink.komma.em.util.UnitOfWork;
import net.enilink.komma.model.IModel;
import net.enilink.komma.model.IModelSet;
import net.enilink.komma.model.IModelSetFactory;
import net.enilink.komma.model.MODELS;
import net.enilink.komma.model.ModelPlugin;
import net.enilink.komma.model.ModelSetModule;
import net.enilink.vocab.rdf.RDF;

/**
 * 
 * Support for RDF-based configuration models on a per-bundle basis.
 * 
 * Any class in a bundle may request a service of type {@link PluginConfigModel}
 * that is either initialized with a file named {bundle.symbolicName}.ttl
 * contained in enilink's configuration directory or a default configuration file
 * named {bundle.symbolicName}-default.ttl contained in the OSGI-INF directory
 * within the bundle itself.
 *
 */
public class PluginConfigManager {
	private static final Logger log = LoggerFactory.getLogger(PluginConfigManager.class);

	private final UnitOfWork uow = new UnitOfWork();
	private IModelSet modelSet;

	private BundleContext context;

	private Config config;

	private Path pluginConfigPath = null;

	private ServiceRegistration<PluginConfigModel> configModelServiceReg = null;

	public void activate() throws InvalidSyntaxException, URISyntaxException {
		this.modelSet = createModelSet();
		// close initial unit of work
		getUnitOfWork().end();
		this.context = FrameworkUtil.getBundle(PluginConfigManager.class).getBundleContext();

		Collection<ServiceReference<Location>> locServices = context.getServiceReferences(Location.class,
				Location.ECLIPSE_HOME_FILTER);
		if (!locServices.isEmpty()) {
			Location location = context.getService(locServices.iterator().next());
			if (location != null) {
				Path path = Paths.get(location.getURL().toURI());
				pluginConfigPath = path.resolve("config");
			}
		}

		configModelServiceReg = context.registerService(PluginConfigModel.class,
				new ServiceFactory<PluginConfigModel>() {
					@Override
					public PluginConfigModel getService(Bundle bundle, ServiceRegistration<PluginConfigModel> r) {
						try {
							uow.begin();
							URI modelUri = URIs.createURI("plugin://" + bundle.getSymbolicName());
							IModel configModel = modelSet.getModel(modelUri, false);
							if (configModel == null) {
								configModel = modelSet.createModel(modelUri);
								loadConfig(bundle, configModel);
							}
							return (PluginConfigModel) configModel;
						} finally {
							uow.end();
						}
					}

					@Override
					public void ungetService(Bundle bundle, ServiceRegistration<PluginConfigModel> r,
							PluginConfigModel cm) {
					}
				}, null);
	}

	private void loadConfig(Bundle bundle, IModel configModel) {
		String pluginName = configModel.getURI().authority();
		URI configFileUri = null;
		if (pluginConfigPath != null) {
			Path configFilePath = pluginConfigPath.resolve(pluginName + ".ttl");
			if (Files.exists(configFilePath)) {
				configFileUri = URIs.createURI(configFilePath.toUri().toString());
			}
		}
		if (configFileUri == null) {
			URL defaultConfigUrl = bundle.getResource("OSGI-INF/" + pluginName + "-default.ttl");
			if (defaultConfigUrl != null) {
				try {
					configFileUri = URIs.createURI(defaultConfigUrl.toURI().toString());
				} catch (URISyntaxException e) {
					log.error("Invalid URI for config file " + defaultConfigUrl, e);
				}
			}
		}
		try {
			InputStream in = configModel.getModelSet().getURIConverter().createInputStream(configFileUri);
			log.debug("Loading config file " + configFileUri);
			configModel.load(in, new HashMap<>());
			configModel.setLoaded(true);
		} catch (IOException e) {
			log.error("Unable to load config file " + configFileUri, e);
		}
	}

	protected Module createModelSetGuiceModule(KommaModule module) {
		return Modules.override(new ModelSetModule(module) {
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
		});
	}

	protected IModelSet createModelSet() {
		KommaModule module = ModelPlugin.createModelSetModule(getClass().getClassLoader());
		module.addConcept(PluginConfigModel.class);

		Injector injector = Guice.createInjector(createModelSetGuiceModule(module), new ContextProviderModule());
		IModelSetFactory factory = injector.getInstance(IModelSetFactory.class);

		URI msUri = URIs.createURI("urn:enilink:config");
		IGraph graph = new LinkedHashGraph();
		graph.add(msUri, RDF.PROPERTY_TYPE, MODELS.TYPE_MODELSET);
		graph.add(msUri, MODELS.NAMESPACE_URI.appendFragment("inference"), false);
		graph.add(msUri, RDF.PROPERTY_TYPE, MODELS.NAMESPACE_URI.appendLocalPart("MemoryModelSet"));

		IModelSet modelSet = factory.createModelSet(msUri, graph);
		return modelSet;
	}

	public synchronized IUnitOfWork getUnitOfWork() {
		return uow;
	}

	public void setConfig(Config config) {
		this.config = config;
	}

	public void deactivate() {
		if (configModelServiceReg != null) {
			configModelServiceReg.unregister();
			configModelServiceReg = null;
		}
		if (modelSet != null) {
			modelSet.dispose();
			modelSet = null;
		}
	}
}