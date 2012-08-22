package net.enilink.core;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import net.enilink.composition.annotations.parameterTypes;
import net.enilink.composition.annotations.precedes;
import net.enilink.composition.concepts.Message;
import net.enilink.composition.traits.Behaviour;

import com.google.inject.Inject;

import net.enilink.komma.common.adapter.AdapterSet;
import net.enilink.komma.common.adapter.IAdapterFactory;
import net.enilink.komma.common.adapter.IAdapterSet;
import net.enilink.komma.concepts.IClass;
import net.enilink.komma.edit.KommaEditPlugin;
import net.enilink.komma.edit.command.EditingDomainCommandStack;
import net.enilink.komma.edit.domain.AdapterFactoryEditingDomain;
import net.enilink.komma.edit.provider.ComposedAdapterFactory;
import net.enilink.komma.edit.provider.ReflectiveItemProviderAdapterFactory;
import net.enilink.komma.model.IModelSet;
import net.enilink.komma.core.URI;

@precedes(IModelSet.class)
public abstract class SessionModelSetSupport implements IModelSet,
		Behaviour<IModelSet> {
	public static class Key {
	};

	private Map<Object, IAdapterSet> sessionScopedAdapterSets = Collections
			.synchronizedMap(new WeakHashMap<Object, IAdapterSet>());

	private ComposedAdapterFactory adapterFactory = new ComposedAdapterFactory(
			ComposedAdapterFactory.IDescriptor.IRegistry.INSTANCE) {
		/**
		 * Default adapter factory for all namespaces
		 */
		class DefaultItemProviderAdapterFactory extends
				ReflectiveItemProviderAdapterFactory {
			public DefaultItemProviderAdapterFactory() {
				super(KommaEditPlugin.getPlugin());
			}

			@Override
			public Object adapt(Object object, Object type) {
				if (object instanceof IClass) {
					// do not override the adapter for classes
					return null;
				}
				return super.adapt(object, type);
			}

			public boolean isFactoryForType(Object type) {
				// support any namespace
				return type instanceof URI || supportedTypes.contains(type);
			}
		}

		DefaultItemProviderAdapterFactory defaultAdapterFactory;
		{
			defaultAdapterFactory = new DefaultItemProviderAdapterFactory();
			defaultAdapterFactory.setParentAdapterFactory(this);
		}

		@Override
		protected IAdapterFactory getDefaultAdapterFactory(Object type) {
			// provide a default adapter factory as fallback if no
			// specific adapter factory was found
			return defaultAdapterFactory;
		}
	};

	@Inject
	protected ISessionProvider sessionProvider;

	@Override
	public IAdapterSet adapters() {
		// use key to make adapter set session scoped
		ISession session = sessionProvider.get();
		Key key = (Key) session.getAttribute(Key.class.getName());
		if (key == null) {
			key = new Key();
			session.setAttribute(Key.class.getName(), key);
		}

		IAdapterSet adapterSet = sessionScopedAdapterSets.get(key);
		if (adapterSet == null) {
			synchronized (sessionScopedAdapterSets) {
				adapterSet = sessionScopedAdapterSets.get(key);
				if (adapterSet == null) {
					adapterSet = new AdapterSet(getBehaviourDelegate());
					sessionScopedAdapterSets.put(key, adapterSet);

					initializeAdapters();
				}
			}
		}

		return adapterSet;
	}

	@parameterTypes({})
	public void dispose(Message msg) {
		// do not dispose this model set
	}

	protected void initializeAdapters() {
		// Create the command stack that will notify this editor as commands
		// are
		// executed.
		EditingDomainCommandStack commandStack = new EditingDomainCommandStack();

		AdapterFactoryEditingDomain editingDomain = new AdapterFactoryEditingDomain(
				adapterFactory, commandStack, getBehaviourDelegate());
		commandStack.setEditingDomain(editingDomain);
		// editingDomain
		// .setModelToReadOnlyMap(new java.util.WeakHashMap<IModel, Boolean>());
	}
}