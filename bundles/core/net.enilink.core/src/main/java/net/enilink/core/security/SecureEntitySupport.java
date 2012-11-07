package net.enilink.core.security;

import net.enilink.vocab.acl.ACL;

import net.enilink.composition.traits.Behaviour;

import net.enilink.komma.core.IQuery;
import net.enilink.komma.core.IReference;

public abstract class SecureEntitySupport implements ISecureEntity,
		Behaviour<ISecureEntity> {
	@Override
	public boolean hasAclMode(IReference user, IReference mode) {
		IQuery<?> query = getEntityManager()
				.createQuery(
						"prefix acl: <"
								+ ACL.NAMESPACE
								+ "> "
								+ "ask { { ?acl acl:accessTo ?target } union { ?acl acl:accessToClass ?class . ?target a ?class } . "
								+ "?acl acl:mode ?mode . "
								+ "{ ?acl acl:agent ?agent } union { ?agent a ?agentClass . ?acl acl:agentClass ?agentClass } }");
		query.setParameter("target", this);
		query.setParameter("agent", user);
		query.setParameter("mode", mode);
		return query.getBooleanResult();
	}
}