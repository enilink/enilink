package net.enilink.core.security;

import java.util.Collection;
import java.util.Set;

import net.enilink.composition.annotations.ParameterTypes;
import net.enilink.composition.cache.annotations.Cacheable;
import net.enilink.composition.traits.Behaviour;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.URI;
import net.enilink.komma.dm.IDataManager;
import net.enilink.komma.em.ThreadLocalDataManager;
import net.enilink.komma.model.IModel;
import net.enilink.komma.model.IModelSet;
import net.enilink.vocab.acl.ENILINKACL;
import net.enilink.vocab.acl.WEBACL;

import org.aopalliance.intercept.MethodInvocation;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.util.Modules;

public abstract class SecureModelSetSupport implements ISecureModelSet,
		Behaviour<ISecureModelSet> {
	static class SecureThreadLocalDataManager extends ThreadLocalDataManager {
		@Inject
		ISecureModelSet modelSet;

		@Override
		protected IDataManager initialValue() {
			return new SecureDataManager(modelSet, super.initialValue());
		}
	}

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
						bind(IDataManager.class).to(
								SecureThreadLocalDataManager.class).in(
								Singleton.class);
					}

					@Provides
					ISecureModelSet provideModelSet() {
						return self;
					}
				});
		modules.clear();
		modules.add(compoundModule);
	}

	@ParameterTypes({ URI.class, String.class })
	public IModel createModel(MethodInvocation invocation) throws Throwable {
		IModel model = (IModel) invocation.proceed();
		if (model != null) {
			URI user = SecurityUtil.getUser();
			if (!(SecurityUtil.UNKNOWN_USER.equals(user) || SecurityUtil.SYSTEM_USER
					.equals(user))
					&& ((ISecureEntity) model).getAclOwner() == null) {
				((ISecureEntity) model).setAclOwner(user);
			}
		}
		return model;
	}

	@Override
	@Cacheable
	public boolean isReadableBy(IReference model, IReference agent) {
		if (model == null
				|| model.equals(((IModelSet.Internal) getBehaviourDelegate())
						.getDefaultGraph())) {
			return true;
		}
		ISecureEntity secureEntity = getMetaDataManager().findRestricted(model,
				ISecureEntity.class);
		if (agent.equals(secureEntity.getAclOwner())) {
			return true;
		}
		Set<IReference> modes = secureEntity.getAclModes(agent);
		return modes.contains(WEBACL.MODE_READ)
				|| modes.contains(ENILINKACL.MODE_WRITERESTRICTED)
				|| modes.contains(WEBACL.MODE_CONTROL);
	}

	@Override
	@Cacheable
	public IReference writeModeFor(IReference model, IReference agent) {
		if (model == null
				|| model.equals(((IModelSet.Internal) getBehaviourDelegate())
						.getDefaultGraph())) {
			return null;
		}
		ISecureEntity secureEntity = getMetaDataManager().findRestricted(model,
				ISecureEntity.class);
		if (agent.equals(secureEntity.getAclOwner())) {
			return WEBACL.MODE_CONTROL;
		}
		Set<IReference> modes = secureEntity.getAclModes(agent);
		IReference mode = null;
		if (modes.contains(WEBACL.MODE_CONTROL)) {
			mode = WEBACL.MODE_CONTROL;
		} else if (modes.contains(WEBACL.MODE_WRITE)) {
			mode = WEBACL.MODE_WRITE;
		} else if (modes.contains(ENILINKACL.MODE_WRITERESTRICTED)) {
			mode = ENILINKACL.MODE_WRITERESTRICTED;
		} else if (modes.contains(WEBACL.MODE_APPEND)) {
			mode = WEBACL.MODE_APPEND;
		}
		return mode;
	}
}
