package net.enilink.platform.ldp.config;

import net.enilink.komma.core.URI;
import net.enilink.platform.ldp.LDP;

import java.util.Set;

public class ContainerHandler extends RdfResourceHandler {
	private boolean creatable;
	private RdfResourceHandler containsHandler;

	public ContainerHandler() {
		super();
		creatable = true;
		containsHandler = new RdfResourceHandler();
	}

	public ContainerHandler(ContainerHandler handler) {
		super();
		if (handler != null) {
			withCreatable(handler.creatable) //
					.withContainsHandler(handler.containsHandler) //
					.withDeletable(handler.isDeletable()) //
					.withModifyable(handler.isModifyable()) //
					.withTypes(handler.getTypes())//
					.withMembershipRelSrcFor(handler.getDirectContainerHandler());
		}
	}

	@Override
	public Set<URI> getTypes() {
		// FIXME: the set might not support modification
		super.getTypes().add(LDP.TYPE_CONTAINER);
		return super.getTypes();
	}

	public boolean isCreatable() {
		return creatable;
	}

	public ContainerHandler withCreatable(boolean creatable) {
		this.creatable = creatable;
		return this;
	}

	public ContainerHandler withContainsHandler(RdfResourceHandler handler) {
		this.containsHandler = handler;
		return this;
	}

	public RdfResourceHandler getContainsHandler() {
		return this.containsHandler;
	}
}
