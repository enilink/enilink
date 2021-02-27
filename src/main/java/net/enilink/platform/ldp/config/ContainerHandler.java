package net.enilink.platform.ldp.config;


public class ContainerHandler extends RdfResourceHandler {
	// default configurations
	private boolean creatable =true;
	private RdfResourceHandler containsHandler = new RdfResourceHandler();
	
	public boolean isCreatable() {
		return creatable;
	}

	public ContainerHandler withCreatable(boolean creatable) {
		this.creatable = creatable;
		return this;
	}

	
	public ContainerHandler withContainsHandler(RdfResourceHandler handler) {
		this.containsHandler = handler;
		return this;
	}
	
	public RdfResourceHandler getContainsHandler() { return this.containsHandler;}

}
