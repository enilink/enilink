package net.enilink.core;

/**
 * Provider interface for {@link ISession} objects.
 */
public interface ISessionProvider {
	/**
	 * Return an {@link ISession} object or <code>null</code>.
	 * 
	 * @return A session or <code>null</code>
	 */
	ISession get();
}