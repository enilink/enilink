package net.enilink.platform.ldp.config;

import java.util.Set;

import net.enilink.komma.core.URI;
import net.enilink.platform.ldp.LDP;

public class DirectContainerHandler extends ContainerHandler {

	private String name;
	private RdfResourceHandler RelSource;
	private URI membership;

	public DirectContainerHandler() {
		super();
		name = "direct-container";
		RelSource = new RdfResourceHandler();
	}

	public DirectContainerHandler(DirectContainerHandler handler) {
		super();
		if (handler != null) {
			withName(handler.getName()) //
					.withRelSource(handler.getRelSource()) //
					.withMembership(handler.getMembership()) //
					.withContainsHandler(handler.getContainsHandler()) //
					.withCreatable(handler.isCreatable()) //
					.withDeletable(handler.isDeletable()) //
					.withModifyable(handler.isModifyable()) //
					.withTypes(handler.getTypes()) //
					.withMembershipRelSrcFor(handler.getDirectContainerHandler());
		}
	}

	@Override
	public Set<URI> getTypes() {
		// FIXME: the set might not support modification
		super.getTypes().add(LDP.TYPE_DIRECTCONTAINER);
		return super.getTypes();
	}

	public String getName() {
		return name;
	}

	public DirectContainerHandler withName(String name) {
		this.name = name;
		return this;
	}

	public RdfResourceHandler getRelSource() {
		return RelSource;
	}

	public DirectContainerHandler withRelSource(RdfResourceHandler relSource) {
		RelSource = relSource;
		return this;
	}

	public URI getMembership() {
		return membership;
	}

	public DirectContainerHandler withMembership(URI membership) {
		this.membership = membership;
		return this;
	}
}
