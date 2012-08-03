package net.enilink.auth;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;

import net.enilink.core.ModelSetManager;
import net.enilink.vocab.auth.AUTH;
import net.enilink.commons.iterator.IExtendedIterator;
import net.enilink.vocab.rdfs.Resource;
import net.enilink.komma.core.IEntityManager;
import net.enilink.komma.core.IQuery;
import net.enilink.komma.core.URI;
import net.enilink.rap.security.callbacks.RegisterCallback;

public class EnilinkLoginModule implements LoginModule {
	private Subject subject;
	private CallbackHandler callbackHandler;
	private boolean isRegister;

	@Override
	public void initialize(Subject subject, CallbackHandler callbackHandler,
			Map<String, ?> sharedState, Map<String, ?> options) {
		this.subject = subject;
		this.callbackHandler = callbackHandler;
		this.isRegister = "register".equalsIgnoreCase(String.valueOf(options
				.get("mode")));
	}

	@Override
	public boolean login() throws LoginException {
		return true;
	}

	@Override
	public boolean commit() throws LoginException {
		List<URI> externalIds = AccountHelper.getExternalIds(subject);

		UserPrincipal userPrincipal = null;
		if (!externalIds.isEmpty()) {
			IEntityManager em = ModelSetManager.INSTANCE.getModelSet()
					.getMetaDataManager();
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
					querySb.append("\t{ ?user ?externalIdProp ?id").append(i)
							.append(" }\n");
					if (i < externalIds.size() - 1) {
						querySb.append("\tunion\n");
					}
				}
				querySb.append("\tfilter isIRI(?user)\n");
				querySb.append("} limit 1");

				IQuery<?> query = em.createQuery(querySb.toString());
				int i = 0;
				for (Iterator<URI> it = externalIds.iterator(); it.hasNext(); i++) {
					query.setParameter("id" + i, it.next());
				}
				query.setParameter("externalIdProp", AUTH.PROPERTY_EXTERNALID);
				IExtendedIterator<Resource> result = query
						.evaluate(Resource.class);
				if (result.hasNext()) {
					userId = result.next().getURI();
				}
			}

			if (!isRegister) {
				// query if this is a 'register' action
				RegisterCallback registerCallback = new RegisterCallback();
				try {
					callbackHandler.handle(new Callback[] { registerCallback });
				} catch (Exception e) {
					// ignore and assume this is not a 'register' action
				}
				isRegister = registerCallback.isRegister();
			}

			if (!isRegister && userId == null) {
				throw new LoginException("Unknown user.");
			}
			if (userId != null) {
				userPrincipal = new UserPrincipal(userId);
			}
		}
		if (userPrincipal != null) {
			subject.getPrincipals().add(userPrincipal);
		}
		return true;
	}

	@Override
	public boolean abort() throws LoginException {
		return true;
	}

	@Override
	public boolean logout() throws LoginException {
		for (UserPrincipal p : subject.getPrincipals(UserPrincipal.class)) {
			subject.getPrincipals().remove(p);
		}
		return true;
	}

}
