package net.enilink.platform.ldp.config;

import java.util.Set;

import net.enilink.komma.core.URI;
import net.enilink.platform.ldp.LDP;

public class ContainerHandler extends RdfResourceHandler {
	private boolean creatable ;
	private RdfResourceHandler containsHandler ;
	
	public ContainerHandler() {
		super();
		creatable =true;
		containsHandler = new RdfResourceHandler();
	}
	
	public ContainerHandler(ContainerHandler handler) {
		new ContainerHandler();
		if(handler != null) {
			withCreatable(handler.creatable).
			withContainsHandler(handler.containsHandler).
            withDeleteable(handler.isDeleteable()).
            withModifyable(handler.isModifyable()).
            withTypes(handler.getTypes()).
            withMembershipRelSrcFor(handler.getDirectContainerHandler());
		}
	}
	@Override
	public Set<URI> getTypes(){
		 super.getTypes().add(LDP.TYPE_CONTAINER);
		 return super.getTypes();
	}
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
