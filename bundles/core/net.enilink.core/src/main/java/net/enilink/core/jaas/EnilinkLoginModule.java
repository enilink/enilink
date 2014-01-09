package net.enilink.core.jaas;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
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
import net.enilink.core.ModelSetManager;
import net.enilink.security.callbacks.RegisterCallback;
import net.enilink.vocab.auth.AUTH;

import org.apache.commons.codec.binary.Base64;

import net.enilink.commons.iterator.IExtendedIterator;
import net.enilink.vocab.rdfs.Resource;
import net.enilink.komma.core.IEntity;
import net.enilink.komma.core.IEntityManager;
import net.enilink.komma.core.IQuery;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIImpl;
import net.enilink.komma.em.concepts.IResource;

public class EnilinkLoginModule implements LoginModule {
	private Subject subject;
	private CallbackHandler callbackHandler;
	private boolean standalone;
	private UserPrincipal userPrincipal;

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

	@Override
	public boolean login() throws LoginException {
		if (standalone) {
			List<Callback> callbacks = new ArrayList<Callback>();
			callbacks.add(new NameCallback("Username: ", "<name>"));
			callbacks.add(new PasswordCallback("Password:", false));
			boolean isRegister = isRegister();
			if (isRegister) {
				callbacks.add(new PasswordCallback("Confirm password:", false));
			}
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
			if (isRegister) {
				char[] confirmedPassword = ((PasswordCallback) callbacks.get(2))
						.getPassword();
				if (!Arrays.equals(password, confirmedPassword)) {
					throw new LoginException("Passwords do not match.");
				}
			}

			String encodedPassword;
			try {
				MessageDigest md = MessageDigest.getInstance("SHA-1");
				md.digest(new String(password).getBytes("UTF-8"));
				encodedPassword = new String(new Base64().encode(md.digest()));
			} catch (Exception e) {
				throw new LoginException("Failed to encode the password: "
						+ e.getMessage());
			}

			URI userId;
			IEntityManager em = ModelSetManager.INSTANCE.getModelSet()
					.getMetaDataManager();
			if (isRegister) {
				try {
					IEntity user = AccountHelper.createUser(em, username);
					user.as(IResource.class).addProperty(
							URIImpl.createURI("urn:enilink:password"),
							encodedPassword);
					userId = user.getURI();
				} catch (IllegalArgumentException iae) {
					throw new LoginException(
							"A user with this name already exists.");
				}
			} else {
				userId = AccountHelper.getUserURI(username);
				boolean found = em
						.createQuery(
								"ask { ?user <urn:enilink:password> ?password }")
						.setParameter("user", userId)
						.setParameter("password", encodedPassword)
						.getBooleanResult();
				if (!found) {
					throw new LoginException("Unknown user or wrong password.");
				}
			}
			userPrincipal = new UserPrincipal(userId);
			return true;
		}
		return true;
	}

	@Override
	public boolean commit() throws LoginException {
		if (userPrincipal == null) {
			List<URI> externalIds = AccountHelper.getExternalIds(subject);
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
						querySb.append("\t{ ?user ?externalIdProp ?id")
								.append(i).append(" }\n");
						if (i < externalIds.size() - 1) {
							querySb.append("\tunion\n");
						}
					}
					querySb.append("\tfilter isIRI(?user)\n");
					querySb.append("} limit 1");

					IQuery<?> query = em.createQuery(querySb.toString());
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
