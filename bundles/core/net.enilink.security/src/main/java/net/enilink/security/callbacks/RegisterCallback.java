package net.enilink.security.callbacks;

import javax.security.auth.callback.Callback;

public class RegisterCallback implements Callback {
	private boolean register;

	public void setRegister(boolean register) {
		this.register = register;
	}

	public boolean isRegister() {
		return register;
	}
}
