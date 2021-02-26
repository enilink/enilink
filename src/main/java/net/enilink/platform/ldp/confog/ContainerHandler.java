package net.enilink.platform.ldp.confog;


public class ContainerHandler extends RdfResourceHandler {
	private boolean creatable =true;

	private RdfResourceHandler containsHandler;
	
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
	
	private RdfResourceHandler getContainsHandler() { return this.containsHandler;}

}
