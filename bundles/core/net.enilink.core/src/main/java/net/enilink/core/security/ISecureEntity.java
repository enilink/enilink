package net.enilink.core.security;

import net.enilink.composition.annotations.Iri;
import net.enilink.komma.core.IEntity;
import net.enilink.komma.core.IReference;

@Iri("http://www.w3.org/2000/01/rdf-schema#Resource")
public interface ISecureEntity extends IEntity {
	@Iri("http://www.w3.org/ns/auth/acl#owner")
	IReference getAclOwner();
	
	void setAclOwner(IReference agent);

	boolean hasAclMode(IReference user, IReference mode);
}