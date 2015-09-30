package net.enilink.auth;

import java.security.Principal;

import net.enilink.komma.core.URI;

/**
 * A user principal identified by a username or an account.
 * 
 */
public final class EnilinkPrincipal implements Principal, java.io.Serializable {
	private static final long serialVersionUID = 2448244491009895974L;

	/**
	 * The principal's uri
	 * 
	 * @serial
	 */
	private final URI id;

	/**
	 * Creates a principal.
	 * 
	 * @param name
	 *            The principal's id.
	 * @exception NullPointerException
	 *                If the <code>name</code> is <code>null</code>.
	 */
	public EnilinkPrincipal(URI id) {
		if (id == null) {
			throw new NullPointerException("null id is illegal");
		}
		this.id = id;
	}

	/**
	 * Compares this principal to the specified object.
	 * 
	 * @param object
	 *            The object to compare this principal against.
	 * @return true if they are equal; false otherwise.
	 */
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (object instanceof EnilinkPrincipal) {
			return id.equals(((EnilinkPrincipal) object).getId());
		}
		return false;
	}

	/**
	 * Returns a hash code for this principal.
	 * 
	 * @return The principal's hash code.
	 */
	public int hashCode() {
		return id.hashCode();
	}

	/**
	 * Returns the id of this principal.
	 * 
	 * @return The principal's id.
	 */
	public URI getId() {
		return id;
	}

	/**
	 * Returns the name of this principal.
	 * 
	 * @return The principal's name.
	 */
	public String getName() {
		return id.localPart();
	}

	/**
	 * Returns a string representation of this principal.
	 * 
	 * @return The principal's name.
	 */
	public String toString() {
		return id.toString();
	}
}
