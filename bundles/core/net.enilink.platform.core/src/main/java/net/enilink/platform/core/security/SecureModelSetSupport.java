package net.enilink.platform.core.security;

import java.util.*;

import com.google.inject.*;
import net.enilink.composition.annotations.ParameterTypes;
import net.enilink.composition.cache.annotations.Cacheable;
import net.enilink.composition.traits.Behaviour;
import net.enilink.komma.common.notify.INotification;
import net.enilink.komma.common.notify.INotificationListener;
import net.enilink.komma.common.notify.NotificationFilter;
import net.enilink.komma.core.*;
import net.enilink.komma.dm.IDataManager;
import net.enilink.komma.em.ThreadLocalDataManager;
import net.enilink.komma.model.IModel;
import net.enilink.komma.model.IModelSet;
import net.enilink.komma.model.base.ModelSetSupport;
import net.enilink.komma.model.concepts.ModelSet;
import net.enilink.komma.model.event.IStatementNotification;
import net.enilink.vocab.acl.ENILINKACL;
import net.enilink.vocab.acl.WEBACL;

import org.aopalliance.intercept.MethodInvocation;

import com.google.inject.util.Modules;

public abstract class SecureModelSetSupport implements IModelSet.Internal, ISecureModelSet,
		Behaviour<ISecureModelSet> {
	protected EntityVar<INotificationListener<INotification>> metaDataListener;

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
	@ParameterTypes({Collection.class})
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

	public void init(Injector injector) {
		metaDataListener.set(new INotificationListener<INotification>() {
			NotificationFilter<INotification> filter = NotificationFilter
					.instanceOf(IStatementNotification.class);
			Set<URI> properties = new HashSet<>(Arrays.asList(WEBACL.PROPERTY_AGENT, WEBACL.PROPERTY_AGENTCLASS, WEBACL.PROPERTY_ACCESSTO,
					WEBACL.PROPERTY_ACCESSTOCLASS, WEBACL.PROPERTY_MODE, WEBACL.PROPERTY_OWNER));

			@Override
			public void notifyChanged(Collection<? extends INotification> notifications) {
				notifications.stream().forEach(notification -> {
					IStatementNotification stmtNotification = (IStatementNotification) notification;
					if (properties.contains(stmtNotification.getPredicate())) {
						// refresh cached properties of this model set
						getMetaDataManager().refresh(getBehaviourDelegate());
					}
				});
			}

			@Override
			public NotificationFilter<INotification> getFilter() {
				return filter;
			}
		});
		getBehaviourDelegate().addMetaDataListener(metaDataListener.get());
	}

	@ParameterTypes({URI.class, String.class})
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
				.getDefaultGraph())
				|| model.equals(((ModelSet) getBehaviourDelegate()).getMetaDataContext())
		) {
			return true;
		}
		ISecureEntity secureEntity = getMetaDataManager().findRestricted(model,
				ISecureEntity.class);
		if (agent.equals(secureEntity.getAclOwner())) {
			return true;
		}
		Set<IReference> modes = secureEntity.getAclModes(agent);
		boolean isReadable = modes.contains(WEBACL.MODE_READ)
				|| modes.contains(ENILINKACL.MODE_WRITERESTRICTED)
				|| modes.contains(WEBACL.MODE_CONTROL);
		if (!isReadable) {
			// use the model's ACLs also for the audit models
			isReadable = Optional.ofNullable(model.getURI())
					.map(uri -> uri.toString())
					.filter(uriStr -> uriStr.startsWith("enilink:audit:"))
					.map(uriStr -> URIs.createURI(uriStr.substring("enilink:audit:".length())))
					.map(uri -> getBehaviourDelegate().isReadableBy(uri, agent))
					.orElse(false);
		}
		return isReadable;
	}

	@Override
	@Cacheable
	public IReference writeModeFor(IReference model, IReference agent) {
		if (model == null
				|| model.equals(((IModelSet.Internal) getBehaviourDelegate())
				.getDefaultGraph())) {
			return null;
		}
		if (model.equals(((ModelSet) getBehaviourDelegate()).getMetaDataContext())) {
			return WEBACL.MODE_WRITE;
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
