package net.enilink.core.security;

import net.enilink.komma.model.IModelSet;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.URI;

public interface ISecureModelSet extends IModelSet {
	boolean isReadableBy(IReference model, URI user);

	boolean isWritableBy(IReference model, URI user);
}