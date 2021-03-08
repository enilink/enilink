package net.enilink.platform.ldp.config;

import java.util.Set;

import net.enilink.komma.core.URI;
import net.enilink.platform.ldp.LDP;

public class DirectContainerHandler extends ContainerHandler{
	private RdfResourceHandler RelSource ;
    private URI membership;
    private String name ;
    
    public DirectContainerHandler() {
    	super();
    	RelSource = new RdfResourceHandler();
    	name ="direct-container";
    }
    public DirectContainerHandler(DirectContainerHandler handler) {
    	new DirectContainerHandler();
    	if(handler != null) {
    		withtName(handler.getName()).
        	withRelSource(handler.getRelSource()).
        	withMembership(handler.getMembership()).
        	withContainsHandler(handler.getContainsHandler()).
            withCreatable(handler.isCreatable()).
            withDeleteable(handler.isDeleteable()).
            withModifyable(handler.isModifyable()).
            withTypes(handler.getTypes()).
            withMembershipRelSrcFor(handler.getDirectContainerHandler());
    	}
    }
    @Override
	public Set<URI> getTypes(){
		 super.getTypes().add(LDP.TYPE_DIRECTCONTAINER);
		 return super.getTypes();
	}
    
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
