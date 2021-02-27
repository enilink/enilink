package net.enilink.platform.ldp.impl;

import net.enilink.composition.traits.Behaviour;
import net.enilink.platform.ldp.LdpResource;

public abstract class LdpResourceSupport implements LdpResource, Behaviour< LdpResource> {
	private String lastEtag="";
	
	@Override
	public boolean hasModified(String tag) {
		return lastEtag.equals(tag);
	}
	
	@Override
	public void setLastEtag(String tag) { this.lastEtag=tag;}

}
