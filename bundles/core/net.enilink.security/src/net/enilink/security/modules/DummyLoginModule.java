package net.enilink.security.modules;

import java.util.HashMap;
import java.util.Map;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.TextOutputCallback;
import javax.security.auth.login.LoginException;

/*
 * example extension configuration for the dummy module:
 *
 *	<extension
 *			id="net.enilink.security.dummyLoginModule"
 *			point="org.eclipse.equinox.security.loginModule">
 *		<loginModule
 *			class="net.enilink.security.DummyLoginModule"
 *			description="Dummy LoginModule">
 *		</loginModule>
 *	</extension>
 *
 *	<extension
 *			id="net.enilink.security.callbackHandler"
 *			point="org.eclipse.equinox.security.callbackHandler">
 *		<callbackHandler
 *			class="net.enilink.security.logindialog.CallbackHandler">
 *		</callbackHandler>
 *	</extension>
 *
 *	<extension
 *			point="org.eclipse.equinox.security.callbackHandlerMapping">
 *		<callbackHandlerMapping
 *			callbackHandlerId="net.enilink.security.callbackHandler"
 *			configName="DUMMY">
 *		</callbackHandlerMapping>
 *	</extension>
 *
 *
 * -------
 *
 * example jaas-config for above configuration
 *
 *	DUMMY {
 *		org.eclipse.equinox.security.auth.module.ExtensionLoginModule required
 *			extensionId="net.enilink.security.dummyLoginModule";
 *	};
 */
public class DummyLoginModule implements javax.security.auth.spi.LoginModule {

	private static final Map<String, String> USERS = new HashMap<String, String>();
	{
		USERS.put("user1", "rap");
		USERS.put("user2", "equinox");
	}
	private CallbackHandler callbackHandler;
	private boolean loggedIn;
	private Subject subject;

	public DummyLoginModule() {
	}

	public void initialize(Subject subject, CallbackHandler callbackHandler,
			Map<String, ?> sharedState, Map<String, ?> options) {
		this.subject = subject;
		this.callbackHandler = callbackHandler;
	}

	public boolean login() throws LoginException {
		Callback label = new TextOutputCallback(TextOutputCallback.INFORMATION,
				"Hint: user1/rap");
		NameCallback nameCallback = new NameCallback("Username:");
		PasswordCallback passwordCallback = new PasswordCallback("Password:",
				false);
		try {
			callbackHandler.handle(new Callback[] { label, nameCallback,
					passwordCallback });
		} catch (Exception e) {
			throw new LoginException(e.getMessage());
		}
		String username = nameCallback.getName();
		String password = "";
		if (passwordCallback.getPassword() != null) {
			password = String.valueOf(passwordCallback.getPassword());
		}
		if (password.equals(USERS.get(username))) {
			loggedIn = true;
			return true;
		}
		throw new LoginException(
				"Combination of provided username and password is invalid.");
	}

	public boolean commit() throws LoginException {
		subject.getPublicCredentials().add(USERS);
		subject.getPrivateCredentials().add("This is private.");
		return loggedIn;
	}

	public boolean abort() throws LoginException {
		loggedIn = false;
		return true;
	}

	public boolean logout() throws LoginException {
		loggedIn = false;
		return true;
	}
}
