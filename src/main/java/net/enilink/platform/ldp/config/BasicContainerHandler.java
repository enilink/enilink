package net.enilink.platform.ldp.config;

import java.util.Set;

import net.enilink.komma.core.URI;
import net.enilink.platform.ldp.LDP;

public class BasicContainerHandler extends ContainerHandler{
	@Override
	public Set<URI> getTypes(){
		 super.getTypes().add(LDP.TYPE_BASICCONTAINER);
		 return super.getTypes();
	}

}
