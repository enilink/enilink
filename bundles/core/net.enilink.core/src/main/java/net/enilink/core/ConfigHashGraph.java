package net.enilink.core;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

import net.enilink.komma.core.IGraph;
import net.enilink.komma.core.ILiteral;
import net.enilink.komma.core.ILiteralFactory;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.IStatement;
import net.enilink.komma.core.IValue;
import net.enilink.komma.core.KommaException;
import net.enilink.komma.core.LinkedHashGraph;
import net.enilink.komma.core.LiteralFactory;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;
import net.enilink.komma.core.visitor.IDataVisitor;
import net.enilink.komma.literals.LiteralConverter;
import net.enilink.komma.model.IURIConverter;
import net.enilink.komma.model.ModelUtil;
import net.enilink.komma.model.base.ExtensibleURIConverter;
import net.enilink.vocab.owl.OWL;
import net.enilink.vocab.xmlschema.XMLSCHEMA;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ForwardingSet;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;

/**
 * A {@link LinkedHashGraph} based implementation of the {@link Config}
 * interface.
 */
class ConfigHashGraph extends LinkedHashGraph implements Config {
	class EmptyConfig extends EmptyGraph implements Config {
		private static final long serialVersionUID = 1L;

		@Override
		public Config filter(IReference subj, IReference pred, Object obj,
				IReference... contexts) {
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

		public FilteredConfig(IReference subj, IReference pred, Object obj,
				IReference[] contexts) {
			super(subj, pred, obj, contexts);
		}

		@Override
		public Config filter(IReference s, IReference p, Object o,
				IReference... c) {
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

	private static final Logger log = LoggerFactory
			.getLogger(ConfigHashGraph.class);
	private static final long serialVersionUID = 1L;

	Config emptyConfig = new EmptyConfig();

	private final LiteralConverter literalConverter;

	{
		literalConverter = Guice.createInjector(new AbstractModule() {
			@Override
			protected void configure() {
				bind(ILiteralFactory.class).to(LiteralFactory.class);
				bind(ClassLoader.class).toInstance(getClass().getClassLoader());
			}
		}).getInstance(LiteralConverter.class);
	}

	@Override
	public boolean add(IReference subj, IReference pred, Object obj,
			IReference... contexts) {
		return super.add(subj, pred, toValue(obj), contexts);
	}

	@Override
	public boolean contains(IReference subj, IReference pred, Object obj,
			IReference... contexts) {
		return super.contains(subj, pred, toValue(obj), contexts);
	}

	@Override
	protected IGraph emptyGraph() {
		return emptyConfig;
	}

	@Override
	public Config filter(IReference subj, IReference pred, Object obj,
			IReference... contexts) {
		return new FilteredConfig(subj, pred, obj, contexts);
	};

	/**
	 * Load platform configuration from a file specified by the system property
	 * {@link ConfigHashGraph#LOCATION_PROPERTY}.
	 */
	public void load() {
		String configLocation = System.getProperty(LOCATION_PROPERTY);
		if (configLocation != null) {
			URI configUri = null;
			if (configUri == null && Files.exists(Paths.get(configLocation))) {
				configUri = URIs.createFileURI(configLocation);
			}
			if (configUri == null) {
				try {
					configUri = URIs.createURI(configLocation);
				} catch (IllegalArgumentException iae) {
				}
			}
			if (configUri == null) {
				log.error("Invalid location of configuration file: {}",
						configLocation);
			} else {
				Set<URI> seen = new HashSet<>();
				final Queue<URI> toLoad = new LinkedList<>();
				toLoad.add(configUri);

				IURIConverter uriConverter = new ExtensibleURIConverter();
				while (!toLoad.isEmpty()) {
					URI uri = toLoad.remove();
					if (seen.add(uri)) {
						try {
							try (InputStream in = new BufferedInputStream(
									uriConverter.createInputStream(uri))) {
								ModelUtil
										.readData(
												in,
												uri.toString(),
												(String) uriConverter
														.contentDescription(
																uri, null)
														.get(IURIConverter.ATTRIBUTE_MIME_TYPE),
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
													public Void visitStatement(
															IStatement stmt) {
														add(stmt);
														if (OWL.PROPERTY_IMPORTS.equals(stmt
																.getPredicate())
																&& stmt.getObject() instanceof IReference) {
															URI imported = ((IReference) stmt
																	.getObject())
																	.getURI();
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
	}

	@Override
	public Object objectInstance() throws KommaException {
		return toInstance(super.objectValue());
	}

	@Override
	public Set<Object> objectInstances() {
		return objectInstances(null, null);
	}

	Set<Object> objectInstances(final IReference subj, final IReference pred,
			final IReference... contexts) {
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
	public boolean remove(IReference subj, IReference pred, Object obj,
			IReference... contexts) {
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
		return literalConverter.createLiteral(String.valueOf(instance),
				XMLSCHEMA.TYPE_STRING);
	}
}
