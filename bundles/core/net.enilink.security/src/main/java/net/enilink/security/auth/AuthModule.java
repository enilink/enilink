package net.enilink.security.auth;

import net.enilink.vocab.acl.Authorization;
import net.enilink.vocab.foaf.FoafModule;
import net.enilink.komma.core.KommaModule;

public class AuthModule extends KommaModule {
	{
		// include FOAF concepts
		includeModule(new FoafModule());

		// include ACL concepts
		addConcept(Authorization.class);
	}
}
