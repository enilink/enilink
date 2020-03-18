package net.enilink.platform.core;

import java.util.Locale;

/**
 * Captures the current user's session and locale.
 * 
 */
public interface IContext {
	/**
	 * Return an {@link ISession} object or <code>null</code>.
	 * 
	 * @return A session or <code>null</code>
	 */
	ISession getSession();

	/**
	 * Return a locale for the current context.
	 * 
	 * @return A locale
	 */
	Locale getLocale();
}
