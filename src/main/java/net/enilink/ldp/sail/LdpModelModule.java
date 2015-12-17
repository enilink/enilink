package net.enilink.ldp.sail;

import net.enilink.komma.core.KommaModule;

public class LdpModelModule extends KommaModule {
	{
		addBehaviour(FederationModelSetSupport.class);
	}
}
