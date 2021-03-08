package net.enilink.platform.ldp.config;

import java.util.HashSet;
import java.util.Set;

import net.enilink.komma.core.URI;

public class RdfResourceHandler implements Handler{
	private boolean deleteble ;
	private boolean modifyable;
	private Set<URI> types ;
    private  DirectContainerHandler membershipRelSrcFor;
    private URI assignedTo;
    
    public RdfResourceHandler() {
    	deleteble = true;
    	modifyable = true;
    	 types = new HashSet<>();
    }
    public RdfResourceHandler(RdfResourceHandler handler) {
    	 new  RdfResourceHandler();
    	 if(handler != null) {
    	 	withDeleteable(handler.deleteble).
    	 	withModifyable(handler.modifyable).
    	 	withTypes(handler.getTypes()).
    	 	withMembershipRelSrcFor(handler.getDirectContainerHandler());
    	 }
    	 
    }
    public RdfResourceHandler withDeleteable(boolean isDeletable) {
    	this.deleteble=isDeletable;
    	return this;
    }
    public RdfResourceHandler withModifyable(boolean isModifyable) {
    	this.modifyable=isModifyable;
    	return this;
    }
    public RdfResourceHandler withTypes(Set<URI>  types) {
    	this.types=types;
    	return this;
    }
    public RdfResourceHandler withMembershipRelSrcFor(DirectContainerHandler   membershipRelSrcFor) {
    	this. membershipRelSrcFor = membershipRelSrcFor;
    	return this;
    } 
    @Override
    public boolean isDeleteable() {
    	return this.deleteble;
    }
    @Override
    public boolean isModifyable() {
    	return this.modifyable;
    }
    public Set<URI> getTypes(){ return this.types; }
    public DirectContainerHandler getDirectContainerHandler() { return this.membershipRelSrcFor;}
    @Override
    public URI getAssignedTo() {
		return assignedTo;
	}
	public RdfResourceHandler withAssignedTo(URI assignedTo) {
		this.assignedTo = assignedTo;
		return this;
	}
}
