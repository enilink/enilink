package net.enilink.auth;

import net.enilink.vocab.acl.Authorization;
import net.enilink.komma.core.KommaModule;
import net.enilink.komma.util.RoleClassLoader;

public class AuthModule extends KommaModule {
	{
		// include FOAF concepts
		RoleClassLoader loader = new RoleClassLoader(this);
		loader.load();

		// include ACL concepts
		addConcept(Authorization.class);
	}
}
