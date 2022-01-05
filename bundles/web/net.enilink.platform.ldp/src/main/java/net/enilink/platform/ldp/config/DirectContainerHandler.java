package net.enilink.platform.ldp.config;

import net.enilink.composition.annotations.Iri;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;
import net.enilink.platform.ldp.LDP;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Set;

public class DirectContainerHandler extends ContainerHandler {

	// magic URI to denote that a container is its own membership resource
	public static URI SELF = URIs.createURI("urn:net.enilink.ldp:resource:self");

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

	/**
	 * Configure the handler from the annotations on the given method.
	 */
	public static DirectContainerHandler forRelation(Method method) {
		if (null == method) {
			return null;
		}
		// use @DirectContainer for container path and delete flag
		DirectContainer dc = method.getAnnotation(DirectContainer.class);
		if (null == dc) {
			return null;
		}
		// use Iri annotation for the membership predicate (hasMemberRelation)
		Iri iri = method.getAnnotation(Iri.class);
		if (null == iri) {
			return null;
		}
		// inspect return value for type of contained resource
		Type r = method.getGenericReturnType();
		Class<?> concept = null;
		if (r instanceof ParameterizedType) {
			ParameterizedType pt = ((ParameterizedType) r);
			if (!Collection.class.isAssignableFrom((Class<?>) pt.getRawType())) {
				return null;
			}
			Type[] t = pt.getActualTypeArguments();
			if (t.length != 1) {
				return null;
			}
			concept = (Class<?>) t[0];
			System.out.println("configuring LDP-DC=" + dc.value() + " for r=" + method.getDeclaringClass() + " m=" + concept);
		}
		// create RdfResourceHandler for concept class return from method
		RdfResourceHandler rh = RdfResourceHandler.fromConcept(concept);
		if (null == rh) {
			return null;
		}
		DirectContainerHandler result = new DirectContainerHandler() //
				.withName(dc.value()) //
				.withMembership(URIs.createURI(iri.value()));
		result.withContainsHandler(rh);
		result.withDeletable(dc.deletable());
		return result;
	}
}
