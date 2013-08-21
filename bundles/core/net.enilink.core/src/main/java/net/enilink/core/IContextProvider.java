package net.enilink.core;

/**
 * Provider interface for user contexts.
 */
public interface IContextProvider {
	/**
	 * Return an {@link IContext} object or <code>null</code>.
	 * 
	 * @return A context or <code>null</code>
	 */
	IContext get();
}