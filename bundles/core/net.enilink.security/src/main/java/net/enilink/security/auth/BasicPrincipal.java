package net.enilink.security.auth;

import java.security.Principal;

/**
 * Simple Principal class, just supporting the bare minimum (a name).
 */
public class BasicPrincipal implements Principal {
	String name;

	public BasicPrincipal(String name) {
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
