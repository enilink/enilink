package net.enilink.auth;

import net.enilink.vocab.acl.Authorization;
import net.enilink.komma.core.KommaModule;
import net.enilink.komma.util.KommaUtil;
import net.enilink.komma.util.RoleClassLoader;

public class AuthModule extends KommaModule {
	{
		// include FOAF concepts
		RoleClassLoader loader = new RoleClassLoader(this);
		loader.load(KommaUtil
				.getBundleMetaInfLocations("net.enilink.vocab.foaf"));

		// include ACL concepts
		addConcept(Authorization.class);
	}
}
