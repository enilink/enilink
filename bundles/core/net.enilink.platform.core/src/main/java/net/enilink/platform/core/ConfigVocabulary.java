package net.enilink.platform.core;

import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;

/**
 * Vocabulary of configuration-related constants used across the enilink platform.
 * <p>
 * This interface centralizes the namespace and configuration property URIs so they
 * can be referenced consistently from other parts of the codebase.
 * </p>
 */
public interface ConfigVocabulary {
	/**
	 * The textual namespace for configuration keys.
	 * This value is used as the base for constructing configuration URIs.
	 */
	String NAMESPACE = "enilink:config/";

	/**
	 * The configuration namespace {@link URI}.
	 */
	URI NAMESPACE_URI = URIs.createURI(NAMESPACE);

	/**
	 * URI identifying the 'queryTimeout' configuration property.
	 * <p>
	 * The property represents the default query timeout value in milliseconds.
	 * </p>
	 */
	URI QUERY_TIMEOUT = URIs.createURI(NAMESPACE + "queryTimeout");
}
