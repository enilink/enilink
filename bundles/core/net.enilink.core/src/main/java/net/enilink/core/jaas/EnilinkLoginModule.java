package net.enilink.core.jaas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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

import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.security.auth.UserPrincipal;

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
 * <p>
 * Options: standalone (without other modules), userMap (pre-defined user/pw
 * list), autoRegister (auto-register user when not found in eniLINK; should not
 * be used together with "anonymous" login modules like OpenID/OAuth)
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
	private EnilinkPrincipal enilinkPrincipal;
	private String userName;

	private IEntityManager entityManager;
	private ServiceReference<IModelSet> modelSetRef;

	private final static Logger logger = LoggerFactory.getLogger(EnilinkLoginModule.class);

	@Override
	public void initialize(Subject subject, CallbackHandler callbackHandler, Map<String, ?> sharedState,
			Map<String, ?> options) {
		this.subject = subject;
		this.callbackHandler = callbackHandler;
		standalone = "standalone".equalsIgnoreCase(String.valueOf(options.get("mode")));
		autoRegister = Boolean.parseBoolean(String.valueOf(options.get("autoRegister")));
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
				if (user == null && autoRegister && userMap.containsKey(username)
						&& Arrays.equals(userMap.get(username), password)) {
					// this will trigger the user-creation in the commit phase
					userName = username;
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
				if (!externalIds.isEmpty() || userName != null) {
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
						// get a suitable username from our own login
						// (standalone -> userName) or from the subject (earlier
						// login module on stack -> principals from subject)
						String username = userName;
						Iterator<UserPrincipal> basePrincipals = subject.getPrincipals(UserPrincipal.class).iterator();
						if (basePrincipals.hasNext()) {
							username = basePrincipals.next().getName();
						}
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
			if (userName != null) {
				userName = null;
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

}
