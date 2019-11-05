package net.enilink.ldp.sail;

import net.enilink.komma.core.KommaModule;

public class LdpModelModule extends KommaModule {
	{
		// LDP external resource access (transparent client)
		addBehaviour(FederationModelSetSupport.class);
	}
}
