package net.enilink.platform.ldp;

import net.enilink.komma.core.KommaModule;
import net.enilink.platform.ldp.impl.BasicContainerSupport;
import net.enilink.platform.ldp.impl.DirectContainerSupport;
import net.enilink.platform.ldp.impl.RdfSourceSupport;

public class LdpModule extends KommaModule {
	{
		// LPD concepts
		addConcept(LdpResource.class);
		addConcept(LdpRdfSource.class);
		addConcept(LdpContainer.class);
		addConcept(LdpBasicContainer.class);
		addConcept(LdpDirectContainer.class);

		// LDP server support
		addBehaviour(RdfSourceSupport.class);
		addBehaviour(BasicContainerSupport.class);
		addBehaviour(DirectContainerSupport.class);
	}
}
