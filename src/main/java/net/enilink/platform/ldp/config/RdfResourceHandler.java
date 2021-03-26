package net.enilink.platform.ldp.config;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import net.enilink.composition.annotations.Iri;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;

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

	static RdfResourceHandler fromConcept(Class<?> concept) {
		if (null == concept) return null;
		RdfResourceHandler result = new RdfResourceHandler();
		Iri iri = concept.getAnnotation(Iri.class);
		if (null != iri) {
			result.withTypes(Collections.singleton(URIs.createURI(iri.value())));
		}
		for (Method m : concept.getMethods()) {
			DirectContainer dc = m.getAnnotation(DirectContainer.class);
			if (null != dc) {
				DirectContainerHandler dch = DirectContainerHandler.forRelation(m);
				if (null != dch) {
					if ("$SELF".equalsIgnoreCase(dc.value())) {
						// special handling if path is set to "$SELF"
						// resource itself is also the container
						// add resource's types to the container
						dch.withTypes(result.getTypes());
						// set the container's membership resource to itself
						// and return the combined handler
						result = dch.withRelSource(dch).withAssignedTo(DirectContainerHandler.SELF);
					} else {
						// otherwise dedicated sub-container using path
						result.withMembershipRelSrcFor(dch);
					}
				}
			}
		}
		return result;
	}
}
