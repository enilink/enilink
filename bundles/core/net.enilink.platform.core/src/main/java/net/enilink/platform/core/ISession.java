package net.enilink.platform.core;

/**
 * Representation of a HTTP session.
 */
public interface ISession {
	/**
	 * Returns the value associated with this name
	 * 
	 * @param name
	 *            - the attribute name
	 * @return - the attribute value associated with this name
	 */
	Object getAttribute(String name);

	/**
	 * Sets a value associated with a name for this session
	 * 
	 * @param name
	 *            - the attribute name
	 * @param value
	 *            - any value
	 */
	void setAttribute(String name, Object value);

	/**
	 * Removes the session attribute having this name
	 * 
	 * @param name
	 *            - the attribute name
	 */
	void removeAttribute(String name);
}
