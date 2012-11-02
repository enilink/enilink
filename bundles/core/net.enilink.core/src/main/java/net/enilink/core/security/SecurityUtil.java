package net.enilink.core.security;

import java.security.AccessController;
import java.util.Set;

import javax.security.auth.Subject;

import net.enilink.auth.UserPrincipal;
import net.enilink.komma.core.URI;

/**
 * Helper class to get current user from the execution context.
 */
public class SecurityUtil {
	/**
	 * Returns the current user or <code>null</code>.
	 * 
	 * @return The id of the current user or <code>null</code>.
	 */
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
