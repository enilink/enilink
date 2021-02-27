package net.enilink.platform.ldp.config;

import net.enilink.komma.core.URI;

public class DirectContainerHandler extends ContainerHandler{
	private RdfResourceHandler RelSource = new RdfResourceHandler();
    private URI membership;
    private String name ="direct-container";
    
	public String getName() {
		return name;
	}
	public DirectContainerHandler withtName(String name) {
		this.name = name;
		return this;
	}
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
