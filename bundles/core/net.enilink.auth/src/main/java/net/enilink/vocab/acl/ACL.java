package net.enilink.vocab.acl;

import net.enilink.komma.core.URIs;
import net.enilink.komma.core.URI;

public interface ACL {
	public static final String NAMESPACE = "http://www.w3.org/ns/auth/acl#";
	public static final URI NAMESPACE_URI = URIs.createURI(NAMESPACE);

	/**
	 * Superclass of all access modes.
	 */
	public static final URI TYPE_ACCESS = NAMESPACE_URI
			.appendLocalPart("Access");

	/**
	 * Append mode.
	 * 
	 * append to the contents without deleting existing data
	 */
	public static final URI TYPE_APPEND = NAMESPACE_URI
			.appendLocalPart("Append");

	/**
	 * Write mode.
	 * 
	 * overwrite the contents (including deleting it, or modifying part of it)
	 */
	public static final URI TYPE_WRITE = NAMESPACE_URI.appendLocalPart("Write");

	/**
	 * Read mode.
	 * 
	 * read the contents (including querying it, etc)
	 */
	public static final URI TYPE_READ = NAMESPACE_URI.appendLocalPart("Read");

	/**
	 * Control mode.
	 * 
	 * set the Access Control List for a resource
	 */
	public static final URI TYPE_CONTROL = NAMESPACE_URI
			.appendLocalPart("Control");

	/**
	 * An Authorization is an abstract thing whose properties are defined in an
	 * Access Control List. The ACL does NOT have to explicitly state that it is
	 * of rdf:type Authorization.
	 */
	public static final URI TYPE_AUTHORIZATION = NAMESPACE_URI
			.appendLocalPart("Authorization");

	/**
	 * The information resource to which access is being granted.
	 */
	public static final URI PROPERTY_ACCESSTO = NAMESPACE_URI
			.appendLocalPart("accessTo");

	/**
	 * A class of information resources to which access is being granted.
	 */
	public static final URI PROPERTY_ACCESSTOCLASS = NAMESPACE_URI
			.appendLocalPart("accessToClass");

	/**
	 * A person or social entity to being given the right.
	 */
	public static final URI PROPERTY_AGENT = NAMESPACE_URI
			.appendLocalPart("agent");

	/**
	 * A class of persons or social entities to being given the right.
	 */
	public static final URI PROPERTY_AGENTCLASS = NAMESPACE_URI
			.appendLocalPart("agentClass");

	/**
	 * A mode of access such as read or write.
	 */
	public static final URI PROPERTY_MODE = NAMESPACE_URI
			.appendLocalPart("mode");

	/**
	 * The person or other agent which owns this. For example, the owner of a
	 * file in a file system. There is a sense of right to control. Typically
	 * defaults to the agent who created something but can be changed.
	 */
	public static final URI PROPERTY_OWNER = NAMESPACE_URI
			.appendLocalPart("owner");
}
