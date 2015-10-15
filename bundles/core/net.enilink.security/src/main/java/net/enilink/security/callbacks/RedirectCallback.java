package net.enilink.security.callbacks;

import java.util.Map;

import javax.security.auth.callback.Callback;

public class RedirectCallback implements Callback {
	private String redirectTo;
	private Map<String, String> requestParameters;

	public RedirectCallback(String redirectTo, Map<String, String> parameters) {
		this.redirectTo = redirectTo;
		this.requestParameters = parameters;
	}

	public String getRedirectTo() {
		return redirectTo;
	}

	public Map<String, String> getRequestParameters() {
		return requestParameters;
	}
}
