package net.enilink.core.security;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import net.enilink.commons.iterator.IExtendedIterator;
import net.enilink.composition.traits.Behaviour;
import net.enilink.komma.core.IQuery;
import net.enilink.komma.core.IReference;
import net.enilink.vocab.acl.ENILINKACL;
import net.enilink.vocab.acl.WEBACL;

public abstract class SecureEntitySupport implements ISecureEntity,
		Behaviour<ISecureEntity> {
	private static Set<IReference> systemUserModes = new HashSet<>(
			Arrays.<IReference> asList(WEBACL.MODE_CONTROL, WEBACL.MODE_READ,
					WEBACL.MODE_WRITE, ENILINKACL.MODE_CREATE));

	@Override
	public Set<IReference> getAclModes(IReference agent) {
		if (SecurityUtil.SYSTEM_USER.equals(agent)) {
			return systemUserModes;
		}
		return queryModes(agent, null).toSet();
	}

	@Override
	public boolean hasAclMode(IReference agent, IReference mode) {
		if (SecurityUtil.SYSTEM_USER.equals(agent)) {
			return systemUserModes.contains(mode);
		}
		try (IExtendedIterator<?> modes = queryModes(agent, mode)) {
			return modes.iterator().hasNext();
		}
	}

	protected IExtendedIterator<IReference> queryModes(IReference agent,
			IReference mode) {
		IQuery<?> query = getEntityManager().createQuery(
				SecurityUtil.QUERY_ACLMODE, false);
		query.setParameter("target", this);
		if (agent != null) {
			query.setParameter("agent", agent);
		}
		if (mode != null) {
			query.setParameter("mode", mode);
		}
		return query.evaluateRestricted(IReference.class);
	}
}