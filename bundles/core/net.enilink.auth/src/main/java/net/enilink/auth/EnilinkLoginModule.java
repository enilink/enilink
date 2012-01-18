package net.enilink.auth;

import java.security.Principal;
import java.security.acl.Group;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;

public class EnilinkLoginModule implements LoginModule {
	private Subject subject;

	@Override
	public void initialize(Subject subject, CallbackHandler callbackHandler,
			Map<String, ?> sharedState, Map<String, ?> options) {
		this.subject = subject;
	}

	@Override
	public boolean login() throws LoginException {
		return true;
	}

	@Override
	public boolean commit() throws LoginException {
		List<String> externalIds = new ArrayList<String>();
		for (Principal principal : subject.getPrincipals()) {
			if (!(principal instanceof Group)) {
				String externalId = "urn:jaas:principal:"
						+ principal.getClass().getName() + "#"
						+ principal.getName();
				externalIds.add(externalId);
			}
		}

		UserPrincipal userPrincipal = null;
		if (!externalIds.isEmpty()) {
			// TODO use RDF store to retrieve internal user id
			userPrincipal = new UserPrincipal(externalIds.iterator().next());
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
