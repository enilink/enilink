package net.enilink.platform.core;

import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

/**
 * Helper class to consume OSGi services.
 * 
 * @param <S>
 *            The service type
 * @param <T>
 *            The result type
 */
public abstract class UseService<S, T> {
	protected T result;

	public UseService(Class<S> serviceClass) {
		BundleContext ctx = FrameworkUtil.getBundle(getClass())
				.getBundleContext();
		ServiceReference<S> serviceRef = ctx.getServiceReference(serviceClass);
		if (serviceRef != null) {
			final S service = ctx.getService(serviceRef);
			if (service != null) {
				try {
					result = withService(service);
				} finally {
					ctx.ungetService(serviceRef);
				}
			}
		}
	}

	protected abstract T withService(S service);

	protected T withoutService() {
		return null;
	}

	public T getResult() {
		return result;
	}
}
