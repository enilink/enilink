package net.enilink.core;

import java.util.Collection;

import net.enilink.composition.annotations.parameterTypes;
import net.enilink.composition.concepts.Message;
import net.enilink.composition.traits.Behaviour;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.util.Modules;

import net.enilink.komma.dm.IDataManager;
import net.enilink.komma.model.IModelSet;

public abstract class SecureModelSetSupport implements IModelSet,
		Behaviour<IModelSet> {
	/**
	 * Ensures that an ACL aware data manager is used to access the models.
	 */
	@parameterTypes({ Collection.class })
	public void collectInjectionModules(Message msg) {
		msg.proceed();

		@SuppressWarnings("unchecked")
		Collection<Module> modules = (Collection<Module>) msg.getParameters()[0];
		Module compoundModule = Modules.override(modules).with(
				new AbstractModule() {
					final IModelSet self = getBehaviourDelegate();

					@Override
					protected void configure() {
						bind(IDataManager.class).to(SecureDataManager.class)
								.in(Singleton.class);
					}

					@Provides
					IModelSet provideModelSet() {
						return self;
					}
				});
		modules.clear();
		modules.add(compoundModule);
	}
}
