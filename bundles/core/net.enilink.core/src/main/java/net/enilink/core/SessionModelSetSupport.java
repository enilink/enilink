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
import net.enilink.komma.common.adapter.IAdapterSet;
import net.enilink.komma.edit.command.EditingDomainCommandStack;
import net.enilink.komma.edit.domain.AdapterFactoryEditingDomain;
import net.enilink.komma.edit.provider.ComposedAdapterFactory;
import net.enilink.komma.model.IModelSet;

@precedes(IModelSet.class)
public abstract class SessionModelSetSupport implements IModelSet,
		Behaviour<IModelSet> {
	public static class Key {
	};

	private Map<Object, IAdapterSet> sessionScopedAdapterSets = Collections
			.synchronizedMap(new WeakHashMap<Object, IAdapterSet>());

	private ComposedAdapterFactory adapterFactory = new ComposedAdapterFactory(
			ComposedAdapterFactory.IDescriptor.IRegistry.INSTANCE);

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