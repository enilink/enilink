package net.enilink.vocab.acl;

import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;

public interface ENILINKACL {
	public static final String NAMESPACE = "http://enilink.net/vocab/acl#";
	public static final URI NAMESPACE_URI = URIs.createURI(NAMESPACE);

	/**
	 * Create mode.
	 * 
	 * Controls if a certain type (rdfs:Class, owl:Class) may be assigned to a
	 * resource.
	 */
	public static final URI MODE_CREATE = NAMESPACE_URI
			.appendLocalPart("Create");

	/**
	 * Restricted mode.
	 * 
	 * Used for models as access mode where reading is allowed but writing is
	 * restricted by embedded ACL constraints.
	 */
	public static final URI MODE_RESTRICTED = NAMESPACE_URI
			.appendLocalPart("Restricted");
}
