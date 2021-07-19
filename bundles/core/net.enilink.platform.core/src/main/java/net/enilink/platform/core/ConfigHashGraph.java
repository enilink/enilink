package net.enilink.platform.core;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

import net.enilink.komma.core.*;
import net.enilink.platform.security.auth.AccountHelper;
import net.enilink.vocab.auth.AUTH;
import net.enilink.vocab.foaf.FOAF;
import org.eclipse.core.runtime.Platform;
import org.eclipse.osgi.service.datalocation.Location;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ForwardingSet;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;

import net.enilink.komma.core.visitor.IDataVisitor;
import net.enilink.komma.literals.LiteralConverter;
import net.enilink.komma.model.IURIConverter;
import net.enilink.komma.model.ModelUtil;
import net.enilink.komma.model.base.ExtensibleURIConverter;
import net.enilink.vocab.owl.OWL;
import net.enilink.vocab.xmlschema.XMLSCHEMA;

/**
 * A {@link LinkedHashGraph} based implementation of the {@link Config}
 * interface.
 */
@Component(service = Config.class)
public class ConfigHashGraph extends LinkedHashGraph implements Config {
	class EmptyConfig extends EmptyGraph implements Config {
		private static final long serialVersionUID = 1L;

		@Override
		public Config filter(IReference subj, IReference pred, Object obj, IReference... contexts) {
			return (Config) super.filter(subj, pred, obj, contexts);
		}

		@Override
		public Object objectInstance() throws KommaException {
			return null;
		}

		@Override
		public Set<Object> objectInstances() {
			return Collections.emptySet();
		}
	}

	class FilteredConfig extends FilteredGraph implements Config {
		private static final long serialVersionUID = 1L;

		public FilteredConfig(IReference subj, IReference pred, Object obj, IReference[] contexts) {
			super(subj, pred, obj, contexts);
		}

		@Override
		public Config filter(IReference s, IReference p, Object o, IReference... c) {
			return (Config) super.filter(s, p, o, c);
		}

		@Override
		public Object objectInstance() throws KommaException {
			return toInstance(objectValue());
		}

		@Override
		public Set<Object> objectInstances() {
			return ConfigHashGraph.this.objectInstances(subj, pred, contexts);
		}
	}

	public static final String LOCATION_PROPERTY = "net.enilink.config";

	public static final String DEFAULT_CONFIG_FILE = "config.ttl";

	private static final Logger log = LoggerFactory.getLogger(ConfigHashGraph.class);
	private static final long serialVersionUID = 1L;

	private final Config emptyConfig = new EmptyConfig();

	private final LiteralConverter literalConverter;

	public ConfigHashGraph() {
		literalConverter = Guice.createInjector(new AbstractModule() {
			@Override
			protected void configure() {
				bind(ILiteralFactory.class).to(LiteralFactory.class);
				bind(ClassLoader.class).toInstance(getClass().getClassLoader());
			}
		}).getInstance(LiteralConverter.class);
	}

	@Override
	public boolean add(IReference subj, IReference pred, Object obj, IReference... contexts) {
		return super.add(subj, pred, toValue(obj), contexts);
	}

	@Override
	public boolean contains(IReference subj, IReference pred, Object obj, IReference... contexts) {
		return super.contains(subj, pred, toValue(obj), contexts);
	}

	@Override
	protected IGraph emptyGraph() {
		return emptyConfig;
	}

	@Override
	public Config filter(IReference subj, IReference pred, Object obj, IReference... contexts) {
		return new FilteredConfig(subj, pred, obj, contexts);
	};

	private static URI getConfigFromLocation(Location location) {
		if (Platform.isRunning()) {
			try {
				// location.getURL does not properly encode paths (see bug 145096)
				// workaround as per https://stackoverflow.com/a/14677157
				Path loc = Paths.get(new java.net.URI(location.getURL().getProtocol(), location.getURL().getPath(), null));
				Path path = loc.resolve(DEFAULT_CONFIG_FILE);
				if (Files.exists(path)) {
					return URIs.createFileURI(path.toAbsolutePath().toString());
				}
			} catch (URISyntaxException e) {
				return null;
			}
		}
		return null;
	}

	/**
	 * Load platform configuration from a file specified by the system property
	 * {@link ConfigHashGraph#LOCATION_PROPERTY}.
	 */
	@Activate
	public void load() {
		URI configUri = null;
		String configLocation = System.getProperty(LOCATION_PROPERTY);
		if (configLocation != null) {
			try {
				Path configPath = Paths.get(configLocation);
				if (Files.exists(configPath)) {
					configUri = URIs.createFileURI(configPath.toAbsolutePath().toString());
				} else {
					log.error("Configuration file not found: {}", configLocation);
				}
			} catch (InvalidPathException ipe) {
				// ignore
			}
			if (configUri == null) {
				try {
					configUri = URIs.createURI(configLocation);
				} catch (IllegalArgumentException iae) {
				}
				if (configUri.isRelative()) {
					// config URI must be absolute
					configUri = null;
					log.error("URI of configuration file must be absolute: {}", configLocation);
				}
			}
		}
		if (Platform.isRunning()) {
			if (configUri == null) {
				configUri = getConfigFromLocation(Platform.getInstanceLocation());
			}
			if (configUri == null) {
				configUri = getConfigFromLocation(Platform.getInstallLocation());
			}
		}
		final Queue<URI> toLoad = new LinkedList<>();
		if (configUri != null) {
			toLoad.add(configUri);
		}
		// load ACL for anonymous access
		if (toLoad.isEmpty() || "all".equals(System.getProperty("net.enilink.acl.anonymous"))) {
			toLoad.add(URIs.createURI("platform:/plugin/net.enilink.platform.core/config/acl-anonymous-all.ttl"));
		}
		if (!toLoad.isEmpty()) {
			Set<URI> seen = new HashSet<>();

			IURIConverter uriConverter = new ExtensibleURIConverter();
			while (!toLoad.isEmpty()) {
				URI uri = toLoad.remove();
				if (seen.add(uri)) {
					try {
						try (InputStream in = new BufferedInputStream(uriConverter.createInputStream(uri))) {
							ModelUtil.readData(in, uri.toString(), (String) uriConverter.contentDescription(uri, null)
									.get(IURIConverter.ATTRIBUTE_MIME_TYPE), new IDataVisitor<Void>() {
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
											add(stmt);
											if (OWL.PROPERTY_IMPORTS.equals(stmt.getPredicate())
													&& stmt.getObject() instanceof IReference) {
												URI imported = ((IReference) stmt.getObject()).getURI();
												if (imported != null) {
													toLoad.add(imported);
												}
											}
											return null;
										}
									});
						}
					} catch (Exception e) {
						log.error("Unable to read config file", e);
					}
				}
			}
		}
	}

	@Override
	public Object objectInstance() throws KommaException {
		return toInstance(super.objectValue());
	}

	@Override
	public Set<Object> objectInstances() {
		return objectInstances(null, null);
	}

	Set<Object> objectInstances(final IReference subj, final IReference pred, final IReference... contexts) {
		final Set<Object> objects = super.objects();
		return new ForwardingSet<Object>() {
			@Override
			protected Set<Object> delegate() {
				return objects;
			}

			@Override
			public Iterator<Object> iterator() {
				final Iterator<Object> base = super.iterator();
				return new Iterator<Object>() {
					@Override
					public boolean hasNext() {
						return base.hasNext();
					}

					@Override
					public Object next() {
						return toInstance(base.next());
					}

					@Override
					public void remove() {
						base.remove();
					}
				};
			}
		};
	}

	@Override
	public boolean remove(IReference subj, IReference pred, Object obj, IReference... contexts) {
		return super.remove(subj, pred, toValue(obj), contexts);
	}

	private Object toInstance(Object value) {
		if (value instanceof ILiteral) {
			return literalConverter.createObject((ILiteral) value);
		}
		return value;
	}

	private IValue toValue(Object instance) {
		if (instance == null) {
			return null;
		}
		if (instance instanceof IValue) {
			return (IValue) instance;
		}

		Class<?> type = instance.getClass();
		if (literalConverter.isDatatype(type)) {
			return literalConverter.createLiteral(instance, null);
		}
		return literalConverter.createLiteral(String.valueOf(instance), XMLSCHEMA.TYPE_STRING);
	}
}
