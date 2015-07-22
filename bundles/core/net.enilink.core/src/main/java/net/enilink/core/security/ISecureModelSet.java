package net.enilink.core.security;

import net.enilink.komma.model.IModelSet;
import net.enilink.komma.core.IReference;

/**
 * Adds ACL based security to model sets.
 */
public interface ISecureModelSet extends IModelSet {
	/**
	 * Tests if an agent is allowed to read data from a specific model of this
	 * set.
	 * 
	 * @param model
	 *            The model reference
	 * @param agent
	 *            The agent identification
	 * @return <code>true</code> if the agent has read access for the model,
	 *         else <code>false</code>.
	 */
	boolean isReadableBy(IReference model, IReference agent);

	/**
	 * Returns the agents write mode for a specific model of this set.
	 * 
	 * @param model
	 *            The model reference
	 * @param agent
	 *            The agent identification
	 * @return The write mode or <code>null</code> if writing is generally
	 *         denied.
	 */
	IReference writeModeFor(IReference model, IReference agent);
}