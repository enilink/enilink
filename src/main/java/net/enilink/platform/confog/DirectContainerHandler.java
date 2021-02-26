package net.enilink.platform.confog;

import net.enilink.komma.core.URI;

public class DirectContainerHandler extends ContainerHandler{
	private RdfResourceHandler RelSource;
    private URI membership;
    
	public RdfResourceHandler getRelSource() {
		return RelSource;
	}
	public DirectContainerHandler withRelSource(RdfResourceHandler relSource) {
		RelSource = relSource;
		return this;
	}
	public URI getMembership() {
		return membership;
	}
	public DirectContainerHandler withMembership(URI membership) {
		this.membership = membership;
		return this;
	}
}
