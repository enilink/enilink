package net.enilink.platform.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.osgi.service.datalocation.Location;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleException;
import org.osgi.framework.BundleListener;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.util.Modules;

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
 * Support for RDF-based configuration models on a per-bundle basis.
 * 
 * Any class in a bundle may request a service of type {@link PluginConfigModel}
 * that is either initialized with a file named {bundle.symbolicName}.ttl
 * contained in enilink's configuration directory or a default configuration
 * file named {bundle.symbolicName}-default.ttl contained in the OSGI-INF
 * directory within the bundle itself.
 */
@Component
public class PluginConfigManager {
	private static final Logger log = LoggerFactory.getLogger(PluginConfigManager.class);

	private final UnitOfWork uow = new UnitOfWork();
	private IModelSet modelSet;

	private BundleContext context;

	private Config config;

	private List<Path> pluginConfigPaths = null;

	private ServiceRegistration<PluginConfigModel> configModelServiceReg = null;

	private Thread watcher;

	@Activate
	public void activate() throws InvalidSyntaxException, URISyntaxException {
		this.modelSet = createModelSet();
		
		// close initial unit of work
		getUnitOfWork().end();
		this.context = FrameworkUtil.getBundle(PluginConfigManager.class).getBundleContext();
		this.pluginConfigPaths = new ArrayList<>();

		for (String locationType : new String[] { Location.INSTANCE_FILTER, Location.ECLIPSE_HOME_FILTER }) {
			Collection<ServiceReference<Location>> locServices = context.getServiceReferences(Location.class,
					locationType);
			if (!locServices.isEmpty()) {
				Location location = context.getService(locServices.iterator().next());
				if (location != null) {
					// location.getURL does not properly encode paths (see bug 145096)
					// workaround as per https://stackoverflow.com/a/14677157
					Path loc = Paths.get(new java.net.URI( //
							location.getURL().getProtocol(), location.getURL().getPath(), null));
					Path configPath = loc.resolve("config");
					if (Files.exists(configPath)) {
						pluginConfigPaths.add(configPath);
					}
				}
			}
		}

		log.info("Config file directories: {}", pluginConfigPaths);

		// watch for configuration changes
		watchForChanges();

		context.addBundleListener(new BundleListener() {
			@Override
			public void bundleChanged(BundleEvent evt) {
				// delete config of uninstalled or updated bundles
				// This is important since otherwise concept and behaviour
				// classes for KOMMA are in a stale state.
				if (evt.getType() == BundleEvent.UNINSTALLED || evt.getType() == BundleEvent.UNRESOLVED) {
					URI modelUri = URIs.createURI("plugin://" + evt.getBundle().getSymbolicName() + "/");
					uow.begin();
					try {
						IModel configModel = modelSet.getModel(modelUri, false);
						if (configModel != null) {
							log.info("Unloading plugin config model: {}", configModel.getURI());

							// delete current config
							modelSet.getDataChangeSupport().setEnabled(null, false);
							configModel.getManager().clear();
							configModel.unloadManager();
							configModel.unload();
							modelSet.getModels().remove(configModel);
						}
					} finally {
						uow.end();
					}
				}
			}
		});

		configModelServiceReg = context.registerService(PluginConfigModel.class,
				new ServiceFactory<PluginConfigModel>() {
					@Override
					public PluginConfigModel getService(Bundle bundle, ServiceRegistration<PluginConfigModel> r) {
						try {
							uow.begin();
							URI modelUri = URIs.createURI("plugin://" + bundle.getSymbolicName() + "/");
							IModel configModel = modelSet.getModel(modelUri, false);
							if (configModel == null || !configModel.isLoaded()) {
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

	private void watchForChanges() {
		if (pluginConfigPaths.isEmpty()) {
			return;
		}
		try {
			final WatchService watchService = FileSystems.getDefault().newWatchService();
			for (Path path : pluginConfigPaths) {
				path.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY,
						StandardWatchEventKinds.ENTRY_DELETE);
			}
			watcher = new Thread(new Runnable() {
				@Override
				public void run() {
					while (true) {
						try {
							WatchKey wk = watchService.take();
							if (wk != null) {
								List<WatchEvent<?>> events = wk.pollEvents();
								if (!events.isEmpty()) {
									for (WatchEvent<?> event : events) {
										WatchEvent.Kind<?> kind = event.kind();

										// An OVERFLOW event can always occur if
										// events are lost or discarded.
										if (kind == StandardWatchEventKinds.OVERFLOW) {
											continue;
										}

										// The filename is the context of the
										// event.
										Path path = (Path) event.context();
										if (path.getFileName().toString().endsWith(".ttl")) {
											String filename = path.getFileName().toString();
											String bundleName = filename.substring(0, filename.lastIndexOf('.'));
											URI modelUri = URIs.createURI("plugin://" + bundleName + "/");

											List<Bundle> affectedBundles = new ArrayList<>();

											uow.begin();
											try {
												IModel model = modelSet.getModel(modelUri, false);
												if (model != null) {
													for (Bundle b : context.getBundles()) {
														if (b.getSymbolicName().startsWith(bundleName)
																&& b.getState() == Bundle.ACTIVE) {
															affectedBundles.add(b);
														}
													}

													// stop bundles
													for (Bundle b : affectedBundles) {
														try {
															b.stop(Bundle.STOP_TRANSIENT);
														} catch (BundleException e) {
															log.error("Unable to stop bundle " + b.getSymbolicName()
																	+ " after configuration change.", e);
														}
													}

													// delete current config
													modelSet.getDataChangeSupport().setEnabled(null, false);
													model.getManager().clear();
													model.unload();

													// reload config
													Path basePath = (Path) wk.watchable();
													Path fullPath = basePath.resolve(path);
													if (Files.exists(fullPath)) {
														try {
															URI configFileUri = URIs
																	.createURI(fullPath.toUri().toString());
															InputStream in = modelSet.getURIConverter()
																	.createInputStream(configFileUri);
															log.debug("Loading config file " + configFileUri);
															Map<Object, Object> options = new HashMap<>();
															options.put(IModel.OPTION_MIME_TYPE, "text/turtle");
															model.load(in, options);
															model.setLoaded(true);
														} catch (IOException e) {
															log.error("Unable to load config file " + fullPath, e);
														}
													}
												}
											} finally {
												uow.end();
											}

											// restart bundle
											for (Bundle b : affectedBundles) {
												try {
													b.start(Bundle.START_TRANSIENT);
												} catch (BundleException e) {
													log.error("Unable to restart bundle " + b.getSymbolicName()
															+ " after configuration change.", e);
												}
											}
										}
									}
									log.debug("Change of plugin configuration detected");
								}
								if (!wk.reset()) {
									break;
								}
							}
						} catch (InterruptedException e) {
							return;
						}
					}
				}
			});
			watcher.setName("Configuration updater");
			watcher.start();
		} catch (IOException e) {
			log.error("Unable to create watcher for configuration directories: " + pluginConfigPaths, e);
		}
	}

	private void loadConfig(Bundle bundle, IModel configModel) {
		String pluginName = configModel.getURI().authority();
		URI configFileUri = null;
		for (Path path : pluginConfigPaths) {
			Path configFilePath = path.resolve(pluginName + ".ttl");
			if (Files.exists(configFilePath)) {
				configFileUri = URIs.createURI(configFilePath.toUri().toString());
				break;
			}
		}
		URI copyFileUri = null;
		if (configFileUri == null) {
			URL defaultConfigUrl = bundle.getResource("OSGI-INF/" + pluginName + "-default.ttl");
			if (defaultConfigUrl != null) {
				try {
					configFileUri = URIs.createURI(defaultConfigUrl.toURI().toString());
					copyFileUri = configFileUri;
				} catch (URISyntaxException e) {
					log.error("Invalid URI for config file " + defaultConfigUrl, e);
				}
			}
		}
		try {
			InputStream in = configModel.getModelSet().getURIConverter().createInputStream(configFileUri);
			log.debug("Loading config file " + configFileUri);
			Map<Object, Object> options = new HashMap<>();
			options.put(IModel.OPTION_MIME_TYPE, "text/turtle");
			configModel.load(in, options);
			configModel.setLoaded(true);
		} catch (IOException e) {
			log.error("Unable to load config file " + configFileUri, e);
		}

		if (!pluginConfigPaths.isEmpty()) {
			if (configFileUri == null) {
				// copy template config file to path
				URL templateConfigUrl = bundle.getResource("OSGI-INF/" + pluginName + "-template.ttl");
				if (templateConfigUrl != null) {
					try {
						copyFileUri = URIs.createURI(templateConfigUrl.toURI().toString());
					} catch (URISyntaxException e) {
						log.error("Invalid URI for config file " + templateConfigUrl, e);
					}
				}
			}

			if (copyFileUri != null) {
				// copy default or template config file to path
				Path targetPath = pluginConfigPaths.get(0);
				Path targetFile = targetPath.resolve(copyFileUri.lastSegment());
				copyToFile(copyFileUri, targetFile);
			}
		}
	}

	protected void copyToFile(URI resoureUri, Path targetFile) {
		if (!Files.exists(targetFile)) {
			try {
				InputStream in = modelSet.getURIConverter().createInputStream(resoureUri);
				Files.copy(in, targetFile);
			} catch (IOException ioe) {
				log.info("Failed to copy config file to path {}", targetFile);
			}
		}
	}

	protected Module createModelSetGuiceModule(KommaModule module) {
		return Modules.override(new ModelSetModule(module) {
			@Override
			protected List<? extends Module> createFactoryModules(KommaModule kommaModule) {
				List<Module> modules = new ArrayList<>(super.createFactoryModules(kommaModule));
				modules.add(new CacheModule());
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
		module.addBehaviour(PluginConfigModelSupport.class);

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

	@Reference
	public void setConfig(Config config) {
		this.config = config;
	}

	@Deactivate
	public void deactivate() {
		if (watcher != null) {
			watcher.interrupt();
			watcher = null;
		}
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
