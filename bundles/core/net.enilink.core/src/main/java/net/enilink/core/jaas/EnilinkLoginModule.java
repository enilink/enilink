package net.enilink.core.jaas;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;

import net.enilink.auth.AccountHelper;
import net.enilink.auth.UserPrincipal;
import net.enilink.commons.iterator.IExtendedIterator;
import net.enilink.core.Activator;
import net.enilink.komma.core.IEntity;
import net.enilink.komma.core.IEntityManager;
import net.enilink.komma.core.IQuery;
import net.enilink.komma.core.URI;
import net.enilink.komma.model.IModelSet;
import net.enilink.security.callbacks.RegisterCallback;
import net.enilink.vocab.auth.AUTH;
import net.enilink.vocab.rdfs.Resource;

import org.osgi.framework.ServiceReference;

public class EnilinkLoginModule implements LoginModule {
	private Subject subject;
	private CallbackHandler callbackHandler;
	private boolean standalone;
	private UserPrincipal userPrincipal;

	private IEntityManager entityManager;
	private ServiceReference<IModelSet> modelSetRef;

	@Override
	public void initialize(Subject subject, CallbackHandler callbackHandler,
			Map<String, ?> sharedState, Map<String, ?> options) {
		this.subject = subject;
		this.callbackHandler = callbackHandler;
		standalone = "standalone".equalsIgnoreCase(String.valueOf(options
				.get("mode")));
	}

	private boolean isRegister() {
		// query if this is a 'register' action
		RegisterCallback registerCallback = new RegisterCallback();
		try {
			callbackHandler.handle(new Callback[] { registerCallback });
		} catch (Exception e) {
			// ignore and assume this is not a 'register' action
			return false;
		}
		return registerCallback.isRegister();
	}

	protected void releaseEntityManager() {
		if (entityManager != null) {
			Activator.getContext().ungetService(modelSetRef);
			entityManager = null;
			modelSetRef = null;
		}
	}

	protected IEntityManager getEntityManager() throws LoginException {
		if (entityManager != null) {
			return entityManager;
		}
		modelSetRef = Activator.getContext().getServiceReference(
				IModelSet.class);
		if (modelSetRef != null) {
			IModelSet modelSet = Activator.getContext().getService(modelSetRef);
			if (modelSet != null) {
				return entityManager = modelSet.getMetaDataManager();
			}
		}
		throw new LoginException("Unable to connect to the user database.");
	}

	@Override
	public boolean login() throws LoginException {
		if (standalone) {
			List<Callback> callbacks = new ArrayList<Callback>();
			callbacks.add(new NameCallback("Username: ", "<name>"));
			callbacks.add(new PasswordCallback("Password:", false));
			try {
				callbackHandler.handle(callbacks.toArray(new Callback[callbacks
						.size()]));
			} catch (java.io.IOException ioe) {
				throw new LoginException(ioe.getMessage());
			} catch (UnsupportedCallbackException uce) {
				throw new LoginException(uce.getMessage()
						+ " not available to garner "
						+ " authentication information from the user");
			}

			String username = ((NameCallback) callbacks.get(0)).getName();
			char[] password = ((PasswordCallback) callbacks.get(1))
					.getPassword();
			String encodedPassword = AccountHelper.encodePassword(new String(
					password));
			URI userId;
			IEntityManager em = getEntityManager();
			try {
				IEntity user = AccountHelper.findUser(em, username,
						encodedPassword);
				if (user == null) {
					throw new LoginException("Unknown user or wrong password.");
				}
				userId = user.getURI();
			} finally {
				releaseEntityManager();
			}
			userPrincipal = new UserPrincipal(userId);
			return true;
		}
		return true;
	}

	@Override
	public boolean commit() throws LoginException {
		try {
			if (userPrincipal == null) {
				List<URI> externalIds = AccountHelper.getExternalIds(subject);
				if (!externalIds.isEmpty()) {
					URI userId = null;
					Iterator<UserPrincipal> principals = subject.getPrincipals(
							UserPrincipal.class).iterator();
					if (principals.hasNext()) {
						userId = principals.next().getId();
					}
					if (userId == null) {
						StringBuilder querySb = new StringBuilder(
								"select ?user where {\n");
						for (int i = 0; i < externalIds.size(); i++) {
							querySb.append("\t{ ?user ?externalIdProp ?id")
									.append(i).append(" }\n");
							if (i < externalIds.size() - 1) {
								querySb.append("\tunion\n");
							}
						}
						querySb.append("\tfilter isIRI(?user)\n");
						querySb.append("} limit 1");

						IQuery<?> query = getEntityManager().createQuery(
								querySb.toString());
						int i = 0;
						for (Iterator<URI> it = externalIds.iterator(); it
								.hasNext(); i++) {
							query.setParameter("id" + i, it.next());
						}
						query.setParameter("externalIdProp",
								AUTH.PROPERTY_EXTERNALID);
						IExtendedIterator<Resource> result = query
								.evaluate(Resource.class);
						if (result.hasNext()) {
							userId = result.next().getURI();
						}
					}
					boolean isRegister = isRegister();
					if (!isRegister && userId == null) {
						throw new LoginException("Unknown user.");
					}
					if (userId != null) {
						userPrincipal = new UserPrincipal(userId);
					}
				}
			}
			if (userPrincipal != null) {
				subject.getPrincipals().add(userPrincipal);
				userPrincipal = null;
			}
			return true;
		} finally {
			releaseEntityManager();
		}
	}

	@Override
	public boolean abort() throws LoginException {
		userPrincipal = null;
		return true;
	}

	@Override
	public boolean logout() throws LoginException {
		for (UserPrincipal p : subject.getPrincipals(UserPrincipal.class)) {
			subject.getPrincipals().remove(p);
		}
		userPrincipal = null;
		return true;
	}

}
