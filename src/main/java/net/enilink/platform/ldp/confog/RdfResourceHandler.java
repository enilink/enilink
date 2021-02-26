package net.enilink.platform.ldp.confog;

import java.util.List;

import net.enilink.komma.core.URI;

public class RdfResourceHandler {
	private boolean deleteble = true;
	private boolean modifyable = true;
	private List<URI> types;
    private  DirectContainerHandler membershipRelSrcFor;
    
    public RdfResourceHandler withDeleteable(boolean isDeletable) {
    	this.deleteble=isDeletable;
    	return this;
    }
    public RdfResourceHandler withModifyable(boolean isModifyable) {
    	this.modifyable=isModifyable;
    	return this;
    }
    public RdfResourceHandler withTypes(List<URI>  types) {
    	this.types=types;
    	return this;
    }
    public RdfResourceHandler withMembershipRelSrcFor(DirectContainerHandler   membershipRelSrcFor) {
    	this. membershipRelSrcFor = membershipRelSrcFor;
    	return this;
    } 
    public boolean isDeleteable() {
    	return this.deleteble;
    }
    public boolean isModifyable() {
    	return this.modifyable;
    }
    public List<URI> getTypes(){ return this.types; }
    public DirectContainerHandler getDirectContainerHandler() { return this.membershipRelSrcFor;}
}
