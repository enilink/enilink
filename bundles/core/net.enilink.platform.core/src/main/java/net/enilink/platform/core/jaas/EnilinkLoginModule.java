package net.enilink.platform.core.jaas;

import net.enilink.komma.core.IEntity;
import net.enilink.komma.core.Statement;
import net.enilink.komma.core.URI;
import net.enilink.komma.model.IModelSet;
import net.enilink.platform.core.Activator;
import net.enilink.platform.core.security.SecurityUtil;
import net.enilink.platform.security.auth.AccountHelper;
import net.enilink.platform.security.auth.EnilinkPrincipal;
import net.enilink.platform.security.callbacks.RegisterCallback;
import net.enilink.vocab.foaf.FOAF;
import net.enilink.vocab.rdf.RDF;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.Subject;
import javax.security.auth.callback.*;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;
import java.security.Principal;
import java.security.acl.Group;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Login module for the eniLINK platform. Usable in a stack of modules
 * (preferrably at the end) or standalone.
 * <ul>
 * Options:
 * <li>mode: "standalone" - without other modules
 * <li>autoRegister: auto-register user when not found in eniLINK; should not be
 * used together with "anonymous" login modules like OpenID/OAuth
 * <li>principalFilter: regular expression to select which Principal will
 * determine the user name for auto-registered users
 * </ul>
 * <p>
 * Example for standalone-mode <blockquote><code><pre>
 * eniLINK {
 *   org.eclipse.equinox.security.auth.module.ExtensionLoginModule required
 *     extensionId="net.enilink.platform.core.EnilinkLoginModule"
 *     mode="standalone";
 * };</pre></code></blockquote>
 * <p>
 * Example for stacked mode with {@link UserMapLoginModule}, auto-register and
 * empty principal filter <blockquote><code><pre>
 * eniLINK {
 *   org.eclipse.equinox.security.auth.module.ExtensionLoginModule required
 *     extensionId="net.enilink.platform.security.UserMapLoginModule"
 *     userMap="[user1:pw1, user2:pw2]";
 *   org.eclipse.equinox.security.auth.module.ExtensionLoginModule required
 *     extensionId="net.enilink.platform.core.EnilinkLoginModule"
 *     autoRegister=true
 *     principalFilter="";
 * };</pre></code></blockquote>
 */
public class EnilinkLoginModule implements LoginModule {
	private Subject subject;
	private CallbackHandler callbackHandler;

	private boolean standalone;
	private boolean autoRegister;
	private String principalFilterRegex;

	private EnilinkPrincipal enilinkPrincipal;

	private IModelSet modelSet;
	private ServiceReference<IModelSet> modelSetRef;

	private final static Logger logger = LoggerFactory.getLogger(EnilinkLoginModule.class);

	@Override
	public void initialize(Subject subject, CallbackHandler callbackHandler, Map<String, ?> sharedState,
						   Map<String, ?> options) {
		this.subject = subject;
		this.callbackHandler = callbackHandler;
		this.standalone = "standalone".equalsIgnoreCase(String.valueOf(options.get("mode")));
		this.autoRegister = Boolean.parseBoolean(String.valueOf(options.get("autoRegister")));
		if (autoRegister && !standalone) {
			// when auto-register is set, but standalone-mode is not, a username
			// needs to be extracted out of results from the other login
			// modules; support a regular expression filter string that defaults
			// to finding an "@" sign in between two non-empty parts
			if (options.containsKey("principalFilter")) {
				principalFilterRegex = String.valueOf(options.get("principalFilter"));
			} else {
				principalFilterRegex = "[^@]{1,}[@][^@]{1,}";
			}
			logger.trace("principalFilter set to '" + principalFilterRegex + "'");
		}
	}

	private boolean isRegister() {
		// query if this is a 'register' action
		RegisterCallback registerCallback = new RegisterCallback();
		try {
			callbackHandler.handle(new Callback[]{registerCallback});
		} catch (Exception e) {
			// ignore and assume this is not a 'register' action
			return false;
		}
		return registerCallback.isRegister();
	}

	protected void releaseModelSet() {
		if (modelSet != null) {
			Activator.getContext().ungetService(modelSetRef);
			modelSet = null;
			modelSetRef = null;
		}
	}

	protected IModelSet getModelSet() throws LoginException {
		if (modelSet != null) {
			return modelSet;
		}
		modelSetRef = Activator.getContext().getServiceReference(IModelSet.class);
		if (modelSetRef != null) {
			IModelSet modelSet = Activator.getContext().getService(modelSetRef);
			if (modelSet != null) {
				return modelSet;
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
			String encodedPassword = password == null ? "" : AccountHelper.encodePassword(new String(password));
			URI userId;
			try {
				IEntity user = AccountHelper.findUser(getModelSet().getMetaDataManager(), username, encodedPassword);
				if (user == null) {
					throw new LoginException("Unknown user or wrong password.");
				}
				userId = user.getURI();
			} finally {
				releaseModelSet();
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
						IEntity user = AccountHelper.findUser(getModelSet().getMetaDataManager(), externalIds);
						if (user != null) {
							userId = user.getURI();
						}
					}
					if (autoRegister && userId == null) {
						String username = null;
						// get a suitable username from the subject's principals
						for (Principal basePrincipal : subject.getPrincipals()) {
							if (!(basePrincipal instanceof Group)) {
								// match name against filter, if set
								// or use as-is
								if (principalFilterRegex == null || principalFilterRegex.isEmpty()
										|| Pattern.matches(principalFilterRegex, basePrincipal.getName())) {
									username = basePrincipal.getName();
								}
							}
						}
						if (username != null) {
							// create this user and link the external IDs
							IEntity user = AccountHelper.createUser(getModelSet().getMetaDataManager(),
									username, null, null);
							if (user != null) {
								Optional.ofNullable(getModelSet().getModel(SecurityUtil.USERS_MODEL,
										false)).ifPresent(model -> {
									model.getManager().add(new Statement(user, RDF.PROPERTY_TYPE, FOAF.TYPE_AGENT));
								});
								userId = user.getURI();
								AccountHelper.linkExternalIds(getModelSet().getMetaDataManager(), userId, externalIds);
								logger.info("auto-registered user '{}' as {}", username, userId);
							}
						} else {
							logger.error("cannot auto-register user: no suitable username found from principals");
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
			releaseModelSet();
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
