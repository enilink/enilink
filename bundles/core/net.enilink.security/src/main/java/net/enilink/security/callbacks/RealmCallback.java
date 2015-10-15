package net.enilink.security.callbacks;

import javax.security.auth.callback.Callback;

public class RealmCallback implements Callback {
	String applicationUrl;
	String contextUrl;

	public String getApplicationUrl() {
		return applicationUrl;
	}

	public String getContextUrl() {
		return contextUrl;
	}

	public void setApplicationUrl(String applicationUrl) {
		this.applicationUrl = applicationUrl;
	}

	public void setContextUrl(String realmUrl) {
		this.contextUrl = realmUrl;
	}
}
