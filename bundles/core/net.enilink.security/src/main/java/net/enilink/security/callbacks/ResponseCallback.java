package net.enilink.security.callbacks;

import java.util.Map;

import javax.security.auth.callback.Callback;

public class ResponseCallback implements Callback {
	private Map<String, String[]> responseParameters;

	public Map<String, String[]> getResponseParameters() {
		return responseParameters;
	}

	public void setResponseParameters(Map<String, String[]> responseParameters) {
		this.responseParameters = responseParameters;
	}

}
