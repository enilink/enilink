package net.enilink.platform.security.modules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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

import net.enilink.platform.security.auth.BasicPrincipal;

/**
 * Simple login module that checks against a list of pre-defined users and, if
 * successful, adds a {@link BasicPrincipal} with the username to the subject
 * to be used by further modules in the stack.
 * <p>
 * Options:
 * <ul>
 * <li>userMap: pre-defined user/pw list</li>
 * </ul>
 */
public class UserMapLoginModule implements LoginModule {
	private Subject subject;
	private CallbackHandler callbackHandler;

	private Map<String, char[]> userMap;

	private BasicPrincipal simplePrincipal;

	@Override
	public void initialize(Subject subject, CallbackHandler callbackHandler, Map<String, ?> sharedState,
			Map<String, ?> options) {
		this.subject = subject;
		this.callbackHandler = callbackHandler;
		// support a list of pre-configured users
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

	@Override
	public boolean login() throws LoginException {
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
		if (userMap.containsKey(username) && Arrays.equals(userMap.get(username), password)) {
			// save principal away for commit()
			simplePrincipal = new BasicPrincipal(username);
			return true;
		}

		throw new LoginException("Unknown user or wrong password.");
	}

	@Override
	public boolean commit() throws LoginException {
		if (simplePrincipal != null) {
			// add saved principal to subject
			subject.getPrincipals().add(simplePrincipal);
			simplePrincipal = null;
			return true;
		}

		throw new LoginException("Unknown user or wrong password.");
	}

	@Override
	public boolean abort() throws LoginException {
		simplePrincipal = null;
		return true;
	}

	@Override
	public boolean logout() throws LoginException {
		for (BasicPrincipal p : subject.getPrincipals(BasicPrincipal.class)) {
			subject.getPrincipals().remove(p);
		}
		simplePrincipal = null;
		return true;
	}
}
