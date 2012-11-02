package net.enilink.core.security;

import net.enilink.komma.model.IModelSet;
import net.enilink.komma.core.IReference;

/**
 * Adds ACL based security to model sets.
 */
public interface ISecureModelSet extends IModelSet {
	/**
	 * Tests if user is allowed to read data from a specific model of this set.
	 * 
	 * @param model
	 *            The model reference
	 * @param user
	 *            The user identification
	 * @return <code>true</code> if the user has read access for the model, else
	 *         <code>false</code>.
	 */
	boolean isReadableBy(IReference model, IReference user);

	/**
	 * Tests if user is allowed to write data to a specific model of this set.
	 * 
	 * @param model
	 *            The model reference
	 * @param user
	 *            The user identification
	 * @return <code>true</code> if the user has write access for the model,
	 *         else <code>false</code>.
	 */
	boolean isWritableBy(IReference model, IReference user);
}