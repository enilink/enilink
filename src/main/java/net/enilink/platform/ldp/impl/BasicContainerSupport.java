package net.enilink.platform.ldp.impl;

import java.util.Set;

import com.google.common.collect.ImmutableSet;

import net.enilink.composition.annotations.Precedes;
import net.enilink.composition.traits.Behaviour;
import net.enilink.komma.core.IReference;
import net.enilink.platform.ldp.LDP;
import net.enilink.platform.ldp.LdpBasicContainer;

@Precedes(RdfSourceSupport.class)
public abstract class BasicContainerSupport implements LdpBasicContainer, Behaviour<LdpBasicContainer> {
	@Override
	public IReference getRelType() {
		return LDP.TYPE_BASICCONTAINER;
	}

	@Override
	public Set<IReference> getTypes() {
		return ImmutableSet.of(LDP.TYPE_CONTAINER, LDP.TYPE_BASICCONTAINER);
	}
	
}
