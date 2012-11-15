package net.enilink.core.security;

import net.enilink.vocab.acl.ACL;

import net.enilink.composition.traits.Behaviour;

import net.enilink.komma.core.IQuery;
import net.enilink.komma.core.IReference;

public abstract class SecureEntitySupport implements ISecureEntity,
		Behaviour<ISecureEntity> {
	@Override
	public boolean hasAclMode(IReference user, IReference mode) {
		if (SecurityUtil.SYSTEM_USER.equals(user)) {
			// the system itself has always sufficient access rights
			return true;
		}
		IQuery<?> query = getEntityManager()
				.createQuery(
						"prefix acl: <"
								+ ACL.NAMESPACE
								+ "> "
								+ "ask { " //
								+ "{ ?target acl:owner ?agent } union {"
								+ "{ ?acl acl:accessTo ?target } union { ?acl acl:accessToClass ?class . ?target a ?class } . "
								+ "?acl acl:mode ?mode . "
								+ "{ ?acl acl:agent ?agent } union { ?agent a ?agentClass . ?acl acl:agentClass ?agentClass }} }");
		query.setParameter("target", this);
		query.setParameter("agent", user);
		query.setParameter("mode", mode);
		return query.getBooleanResult();
	}
}