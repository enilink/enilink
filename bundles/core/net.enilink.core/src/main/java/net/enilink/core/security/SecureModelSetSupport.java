package net.enilink.core.security;

import java.util.Collection;

import net.enilink.vocab.acl.ACL;

import org.aopalliance.intercept.MethodInvocation;
import net.enilink.composition.annotations.ParameterTypes;
import net.enilink.composition.cache.annotations.Cacheable;
import net.enilink.composition.traits.Behaviour;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.util.Modules;

import net.enilink.komma.dm.IDataManager;
import net.enilink.komma.model.IModelSet;
import net.enilink.komma.core.IReference;

public abstract class SecureModelSetSupport implements ISecureModelSet,
		Behaviour<ISecureModelSet> {
	/**
	 * Ensures that an ACL aware data manager is used to access the models.
	 */
	@ParameterTypes({ Collection.class })
	public void collectInjectionModules(MethodInvocation invocation)
			throws Throwable {
		invocation.proceed();

		@SuppressWarnings("unchecked")
		Collection<Module> modules = (Collection<Module>) invocation
				.getArguments()[0];
		Module compoundModule = Modules.override(modules).with(
				new AbstractModule() {
					final ISecureModelSet self = getBehaviourDelegate();

					@Override
					protected void configure() {
						bind(IDataManager.class).to(SecureDataManager.class)
								.in(Singleton.class);
					}

					@Provides
					ISecureModelSet provideModelSet() {
						return self;
					}
				});
		modules.clear();
		modules.add(compoundModule);
	}

	protected boolean hasAclMode(IReference model, IReference user,
			IReference mode) {
		return getMetaDataManager().findRestricted(model, ISecureEntity.class)
				.hasAclMode(user, mode);
	}

	@Override
	@Cacheable
	public boolean isReadableBy(IReference model, IReference user) {
		if (model == null
				|| model.equals(((IModelSet.Internal) getBehaviourDelegate())
						.getDefaultGraph())) {
			return true;
		}
		return hasAclMode(model, user, ACL.TYPE_READ);
	}

	@Override
	@Cacheable
	public boolean isWritableBy(IReference model, IReference user) {
		if (model == null
				|| model.equals(((IModelSet.Internal) getBehaviourDelegate())
						.getDefaultGraph())) {
			return false;
		}
		return hasAclMode(model, user, ACL.TYPE_WRITE);
	}
}
