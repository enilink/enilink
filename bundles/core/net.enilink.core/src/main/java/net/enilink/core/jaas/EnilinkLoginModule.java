package net.enilink.core.jaas;

import java.security.Principal;
import java.security.acl.Group;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;

import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.enilink.auth.AccountHelper;
import net.enilink.auth.EnilinkPrincipal;
import net.enilink.core.Activator;
import net.enilink.komma.core.IEntity;
import net.enilink.komma.core.IEntityManager;
import net.enilink.komma.core.URI;
import net.enilink.komma.model.IModelSet;
import net.enilink.security.callbacks.RegisterCallback;

/**
 * Login module for the eniLINK platform. Usable in a stack of modules
 * (preferrably at the end) or standalone.
 * <ul>
 * Options:
 * <li>mode: "standalone" - without other modules
 * <li>userMap: pre-defined user/pw list
 * <li>autoRegister: auto-register user when not found in eniLINK; should not be
 * used together with "anonymous" login modules like OpenID/OAuth
 * <li>principalFilter: regular expression to select which Principal will
 * determine the user name for auto-registered users
 * </ul>
 * <p>
 * Example for standalone-mode with pre-defined users and auto-register
 * <blockquote><code><pre>
 * eniLINK {
 *   org.eclipse.equinox.security.auth.module.ExtensionLoginModule required
 *     extensionId="net.enilink.core.EnilinkLoginModule"
 *     mode="standalone"
 *     userMap="[user1:pw1, user2:pw2]"
 *     autoRegister=true;
 * };</pre></code></blockquote>
 */
public class EnilinkLoginModule implements LoginModule {
	private Subject subject;
	private CallbackHandler callbackHandler;

	private boolean standalone;
	private boolean autoRegister;
	private Map<String, char[]> userMap;
	private String principalFilterRegex;

	private EnilinkPrincipal enilinkPrincipal;

	private IEntityManager entityManager;
	private ServiceReference<IModelSet> modelSetRef;

	private final static Logger logger = LoggerFactory.getLogger(EnilinkLoginModule.class);

	@Override
	public void initialize(Subject subject, CallbackHandler callbackHandler, Map<String, ?> sharedState,
			Map<String, ?> options) {
		this.subject = subject;
		this.callbackHandler = callbackHandler;
		this.standalone = "standalone".equalsIgnoreCase(String.valueOf(options.get("mode")));
		if (standalone) {
			// in standalone mode, support a list of pre-configured users
			// syntax for the option is: userMap = [user1:pw1, user2:pw2, ...]
			this.userMap = new HashMap<String, char[]>();
			String mapStr = String.valueOf(options.get("userMap"));
			if (null != mapStr && mapStr.startsWith("[") && mapStr.endsWith("]")) {
				mapStr = mapStr.substring(1, mapStr.length() - 1);
				String[] mapParts = mapStr.trim().split(",");
				for (String mapPart : mapParts) {
					String[] userPw = mapPart.trim().split(":");
					if (userPw.length == 2) {
						userMap.put(userPw[0].trim(), userPw[1].trim().toCharArray());
					}
				}
			}
		}
		this.autoRegister = Boolean.parseBoolean(String.valueOf(options.get("autoRegister")));
		if (autoRegister && !standalone) {
			// when auto-register is set, but standalone-mode is not, a username
			// needs to be extracted out of results from the other login modules
			// support a regular expression filter string that defaults to find
			// an "@" sign in between two non-empty parts
			if (options.containsKey("principalFilter")) {
				principalFilterRegex = String.valueOf(options.get("principalFilter"));
			} else {
				principalFilterRegex = "[^@]{1,}@[^@]{1,}";
			}
		}
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
		modelSetRef = Activator.getContext().getServiceReference(IModelSet.class);
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
				callbackHandler.handle(callbacks.toArray(new Callback[callbacks.size()]));
			} catch (java.io.IOException ioe) {
				throw new LoginException(ioe.getMessage());
			} catch (UnsupportedCallbackException uce) {
				throw new LoginException(
						uce.getMessage() + " not available to garner " + " authentication information from the user");
			}

			String username = ((NameCallback) callbacks.get(0)).getName();
			char[] password = ((PasswordCallback) callbacks.get(1)).getPassword();
			String encodedPassword = AccountHelper.encodePassword(new String(password));
			URI userId;
			IEntityManager em = getEntityManager();
			try {
				IEntity user = AccountHelper.findUser(em, username, encodedPassword);
				// if the user account does not exist and autoRegister is set,
				// check against the list of pre-defined users
				// FIXME: maybe get rid of the user map here and add it to a
				// simple login module for use earlier in the stack
				if (user == null && autoRegister && userMap.containsKey(username)
						&& Arrays.equals(userMap.get(username), password)) {
					// add a LocalPrincipal for this user to the subject
					// will get linked as external ID to the user entry
					subject.getPrincipals().add(new LocalPrincipal(username));
					return true;
				}
				if (user == null) {
					throw new LoginException("Unknown user or wrong password.");
				}
				userId = user.getURI();
			} finally {
				releaseEntityManager();
			}
			enilinkPrincipal = new EnilinkPrincipal(userId);
			return true;
		}
		return true;
	}

	@Override
	public boolean commit() throws LoginException {
		try {
			if (enilinkPrincipal == null) {
				List<URI> externalIds = AccountHelper.getExternalIds(subject);
				if (!externalIds.isEmpty()) {
					URI userId = null;
					Iterator<EnilinkPrincipal> principals = subject.getPrincipals(EnilinkPrincipal.class).iterator();
					if (principals.hasNext()) {
						userId = principals.next().getId();
					}
					if (userId == null) {
						IEntity user = AccountHelper.findUser(getEntityManager(), externalIds);
						if (user != null) {
							userId = user.getURI();
						}
					}
					if (autoRegister && userId == null) {
						String username = null;
						// get a suitable username from the subject's principals
						for (Principal basePrincipal : subject.getPrincipals()) {
							if (!(basePrincipal instanceof Group)) {
								if (principalFilterRegex == null || principalFilterRegex.isEmpty()
										|| Pattern.matches(principalFilterRegex, basePrincipal.getName())) {
									username = basePrincipal.getName();
								}
							}
						}
						// create this user and link the external IDs
						if (username != null) {
							IEntity user = AccountHelper.createUser(getEntityManager(), username, null, null);
							if (user != null) {
								userId = user.getURI();
								AccountHelper.linkExternalIds(getEntityManager(), userId, externalIds);
								logger.info("auto-registered user '{}' as {}", username, userId);
							}
						} else {
							logger.error("cannot auto-register user: no username/principal");
						}

					}
					boolean isRegister = isRegister();
					if (!isRegister && userId == null) {
						throw new LoginException("Unknown user.");
					}
					if (userId != null) {
						enilinkPrincipal = new EnilinkPrincipal(userId);
					}
				}
			}
			if (enilinkPrincipal != null) {
				subject.getPrincipals().add(enilinkPrincipal);
				enilinkPrincipal = null;
			} else {
				throw new LoginException("Unknown user.");
			}
			return true;
		} finally {
			releaseEntityManager();
		}
	}

	@Override
	public boolean abort() throws LoginException {
		enilinkPrincipal = null;
		return true;
	}

	@Override
	public boolean logout() throws LoginException {
		for (EnilinkPrincipal p : subject.getPrincipals(EnilinkPrincipal.class)) {
			subject.getPrincipals().remove(p);
		}
		enilinkPrincipal = null;
		return true;
	}

	/**
	 * Simple Principal class for the users defined in the user map.
	 */
	public static class LocalPrincipal implements Principal {
		String name;

		public LocalPrincipal(String name) {
			this.name = name;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public String toString() {
			return name;
		}
	}
}
