package net.enilink.platform.workbench.components;

import net.enilink.platform.core.IContext;
import net.enilink.platform.core.IContextProvider;
import net.enilink.platform.core.ISession;
import org.eclipse.rap.rwt.RWT;
import org.eclipse.swt.widgets.Display;
import org.osgi.service.component.annotations.Component;

@Component(service = IContextProvider.class)
public class ContextProvider implements IContextProvider {
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
}
