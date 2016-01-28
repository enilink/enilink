package net.enilink.poweroff;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

public class Activator implements BundleActivator {

	// use short default timeout for TERM/INT/... signals
	public final static int DEFAULT_TERMINATION_TIMEOUT = 5;
	public final static String PROPERTY_TERMINATION_TIMEOUT = "net.enilink.poweroff.termination.timeout";

	private Thread shutdownHook;

	@Override
	public void start(final BundleContext context) throws Exception {
		shutdownHook = new Thread() {
			@Override
			public void run() {
				// paranoia: don't initiate twice
				if (Poweroff.wasStarted()) {
					return;
				}
				int timeout = DEFAULT_TERMINATION_TIMEOUT;
				try {
					// prefer value of configuration property over default
					timeout = Integer.parseInt( //
							System.getProperty(PROPERTY_TERMINATION_TIMEOUT,
									String.valueOf(DEFAULT_TERMINATION_TIMEOUT)));
				} catch (NumberFormatException ignored) {
				}
				System.out.println("Received termination signal, powering off (" + timeout + "s timeout)...");
				Poweroff.poweroff(context, timeout);
				// exit status from poweroff can't be set from here
			}
		};
		shutdownHook.setDaemon(true);
		try {
			Runtime.getRuntime().addShutdownHook(shutdownHook);
		} catch (Throwable t) {
			System.err.println("Poweroff: Unable to register shutdown hook, no proper shutdown on termination!");
		}
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		try {
			Runtime.getRuntime().removeShutdownHook(shutdownHook);
		} catch (Throwable ignored) {
		}
	}
}
