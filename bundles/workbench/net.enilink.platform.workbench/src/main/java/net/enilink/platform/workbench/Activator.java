package net.enilink.platform.workbench;

import net.enilink.platform.core.IContext;
import net.enilink.platform.core.IContextProvider;
import net.enilink.platform.core.ISession;
import org.eclipse.rap.rwt.RWT;
import org.eclipse.swt.widgets.Display;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

public class Activator implements BundleActivator {
	private static BundleContext context;
	private ServiceRegistration<?> contextServiceReg;

	@Override
	public void start(BundleContext context) throws Exception {
		Activator.context = context;
		contextServiceReg = context.registerService(IContextProvider.class, new IContextProvider() {
			final ISession session = new ISession() {
				@Override
				public void setAttribute(String name, Object value) {
					RWT.getUISession().setAttribute(name, value);
				}

				@Override
				public void removeAttribute(String name) {
					RWT.getUISession().removeAttribute(name);
				}

				@Override
				public Object getAttribute(String name) {
					return RWT.getUISession().getAttribute(name);
				}
			};
			final IContext context = new IContext() {
				public ISession getSession() {
					return session;
				}

				public java.util.Locale getLocale() {
					return RWT.getLocale();
				}
			};

			@Override
			public IContext get() {
				// if we are on the RAP UI thread then return the RAP
				// context else null
				if (Display.getCurrent() != null) {
					return context;
				}
				return null;
			}
		}, null);
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		if (contextServiceReg != null) {
			contextServiceReg.unregister();
			contextServiceReg = null;
		}
		Activator.context = null;
	}

	public static BundleContext getContext() {
		return context;
	}
}
