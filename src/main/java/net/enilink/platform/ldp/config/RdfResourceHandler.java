package net.enilink.platform.ldp.config;

import java.util.HashSet;
import java.util.Set;

import net.enilink.komma.core.URI;

public class RdfResourceHandler implements Handler {

	private boolean separateModel;
	private boolean deletable;
	private boolean modifyable;
	private Set<URI> types;
	private DirectContainerHandler membershipRelSrcFor;
	private URI assignedTo;

	public RdfResourceHandler() {
		deletable = true;
		modifyable = true;
		types = new HashSet<>();
	}

	public RdfResourceHandler(RdfResourceHandler handler) {
		if (handler != null) {
			this.withSeparateModel(handler.separateModel) //
					.withDeletable(handler.deletable) //
					.withModifyable(handler.modifyable) //
					.withTypes(handler.getTypes()) //
					.withMembershipRelSrcFor(handler.getDirectContainerHandler());
		}
	}

	public RdfResourceHandler withSeparateModel(boolean separateModel) {
		this.separateModel = separateModel;
		return this;
	}

	public RdfResourceHandler withDeletable(boolean isDeletable) {
		this.deletable = isDeletable;
		return this;
	}

	public RdfResourceHandler withModifyable(boolean isModifyable) {
		this.modifyable = isModifyable;
		return this;
	}

	public RdfResourceHandler withTypes(Set<URI> types) {
		this.types = new HashSet<>(types); // FIXME: due to ContainerHandler calling add()
		return this;
	}

	public RdfResourceHandler withMembershipRelSrcFor(DirectContainerHandler membershipRelSrcFor) {
		this.membershipRelSrcFor = membershipRelSrcFor;
		return this;
	}

	public boolean isSeparateModel() {
		return this.separateModel;
	}

	@Override
	public boolean isDeletable() {
		return this.deletable;
	}

	@Override
	public boolean isModifyable() {
		return this.modifyable;
	}

	public Set<URI> getTypes() {
		return this.types;
	}

	public DirectContainerHandler getDirectContainerHandler() {
		return this.membershipRelSrcFor;
	}

	@Override
	public URI getAssignedTo() {
		return assignedTo;
	}

	public RdfResourceHandler withAssignedTo(URI assignedTo) {
		this.assignedTo = assignedTo;
		return this;
	}
}
