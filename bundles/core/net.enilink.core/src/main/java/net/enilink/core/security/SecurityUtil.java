package net.enilink.core.security;

import java.security.AccessController;
import java.util.Set;

import javax.security.auth.Subject;

import net.enilink.auth.UserPrincipal;
import net.enilink.komma.core.URI;

public class SecurityUtil {
	public static URI getUserId() {
		Subject s = Subject.getSubject(AccessController.getContext());
		if (s != null) {
			Set<UserPrincipal> principals = s
					.getPrincipals(UserPrincipal.class);
			if (!principals.isEmpty()) {
				return principals.iterator().next().getId();
			}
		}
		return null;
	}
}
