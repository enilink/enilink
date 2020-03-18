package net.enilink.platform.core;

import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;

import com.google.inject.AbstractModule;

public class ContextProviderModule extends AbstractModule {
	
	static IContextProvider contextProvider = new IContextProvider() {
		@Override
		public IContext get() {
			try {
				// get the first valid context from any
				// registered session provider service
				BundleContext bundleContext = Activator.getContext();
				for (ServiceReference<IContextProvider> spRef : bundleContext
						.getServiceReferences(IContextProvider.class, null)) {
					IContext userCtx = bundleContext.getService(spRef).get();
					bundleContext.ungetService(spRef);
					if (userCtx != null) {
						return userCtx;
					}
				}
			} catch (InvalidSyntaxException ise) {
				// ignore
			}
			return null;
		}
	};
	
	@Override
	protected void configure() {
		bind(IContextProvider.class).toInstance(contextProvider);
	}
}