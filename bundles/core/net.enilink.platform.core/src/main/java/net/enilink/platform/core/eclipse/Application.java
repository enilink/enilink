package net.enilink.platform.core.eclipse;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

public class Application implements IApplication {

	@Override
	public Object start(IApplicationContext context) throws Exception {
		return IApplicationContext.EXIT_ASYNC_RESULT;
	}

	@Override
	public void stop() {
	}

}
