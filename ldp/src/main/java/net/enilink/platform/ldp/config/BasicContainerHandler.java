package net.enilink.platform.ldp.config;

import net.enilink.komma.core.URI;
import net.enilink.platform.ldp.LDP;

import java.util.Set;

public class BasicContainerHandler extends ContainerHandler {

	private final String path;

	public BasicContainerHandler(String path) {
		this.path = path;
	}

	@Override
	public Set<URI> getTypes() {
		// FIXME: the set might not support modification
		super.getTypes().add(LDP.TYPE_BASICCONTAINER);
		return super.getTypes();
	}

	public String getPath() {
		return path;
	}

	/** Configure the handler from the annotations in the given concept class. */
	public static BasicContainerHandler fromConcept(Class<?> concept) {
		BasicContainer bc = concept.getAnnotation(BasicContainer.class);
		if (null == bc) return null;
		RdfResourceHandler rh = RdfResourceHandler.fromConcept(concept).withSeparateModel(true);
		if (null == rh) return null;
		System.out.println("configuring LDP-BC=" + bc.value() + " for m=" + concept);
		BasicContainerHandler result = new BasicContainerHandler(bc.value());
		result.withDeletable(bc.deletable());
		result.withContainsHandler(rh);
		return result;
	}
}
