package net.enilink.core.security;

import java.util.Set;

import net.enilink.composition.annotations.Iri;
import net.enilink.komma.core.IEntity;
import net.enilink.komma.core.IReference;

@Iri("http://www.w3.org/2000/01/rdf-schema#Resource")
public interface ISecureEntity extends IEntity {
	Set<IReference> getAclModes(IReference agent);

	@Iri("http://www.w3.org/ns/auth/acl#owner")
	IReference getAclOwner();

	boolean hasAclMode(IReference agent, IReference mode);

	void setAclOwner(IReference agent);
}