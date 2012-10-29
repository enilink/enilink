package net.enilink.core.security;

import java.util.Collection;

import net.enilink.vocab.acl.ACL;

import net.enilink.composition.annotations.parameterTypes;
import net.enilink.composition.cache.annotations.Cacheable;
import net.enilink.composition.concepts.Message;
import net.enilink.composition.traits.Behaviour;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.util.Modules;

import net.enilink.komma.dm.IDataManager;
import net.enilink.komma.model.IModelSet;
import net.enilink.komma.core.IQuery;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.URI;

public abstract class SecureModelSetSupport implements ISecureModelSet,
		Behaviour<ISecureModelSet> {
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

	protected boolean hasAclMode(IReference model, URI user, URI mode) {
		IQuery<?> query = getMetaDataManager()
				.createQuery(
						"prefix acl: <"
								+ ACL.NAMESPACE
								+ "> "
								+ "ask { ?acl acl:accessTo ?target . ?acl acl:mode ?mode . "
								+ "{ ?acl acl:agent ?agent } union { ?agent a ?agentClass . ?acl acl:agentClass ?agentClass } }");
		query.setParameter("target", model);
		query.setParameter("agent", user);
		query.setParameter("mode", mode);
		return query.getBooleanResult();
	}

	@Override
	@Cacheable
	public boolean isReadableBy(IReference model, URI user) {
		if (model == null
				|| model.equals(((IModelSet.Internal) getBehaviourDelegate())
						.getDefaultGraph())) {
			return true;
		}
		return hasAclMode(model, user, ACL.TYPE_READ);
	}

	@Override
	@Cacheable
	public boolean isWritableBy(IReference model, URI user) {
		if (model == null
				|| model.equals(((IModelSet.Internal) getBehaviourDelegate())
						.getDefaultGraph())) {
			return false;
		}
		return hasAclMode(model, user, ACL.TYPE_WRITE);
	}
}
