package net.enilink.poweroff;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

/**
 * Implementation of a proper poweroff sequence that stops the system bundle and
 * waits at most timeout seconds before returning to the caller.
 * <p>
 * Used in a ShutdownHook to cleanly shut down all bundles on JVM termination
 * and for the poweroff osgi/gogo shell command.
 */
public class Poweroff {

	public final static int STATUS_OK = 0;
	public final static int STATUS_TIMEOUTEXCEEDED = 1;
	public final static int STATUS_INPROGRESS = 2;
	public final static int STATUS_FAILED = -1;

	protected static Thread showStopper = null;

	/**
	 * Powers off the OSGi framework by stopping the OSGi system bundle, then
	 * waiting at most timeout seconds for all other bundles to stop gracefully.
	 * 
	 * @param context
	 *            The OSGi BundleContext, needed to access the system bundle and
	 *            check for the state of other bundles.
	 * @param timeout
	 *            The timeout, in seconds, to wait for the bundles to stop
	 *            before shutting down.
	 * @return Value suitable as exit status, indicating success or failure of
	 *         poweroff operation.
	 */
	public static int poweroff(final BundleContext context, final int timeout) {
		// don't initiate twice
		if (wasStarted()) {
			return STATUS_INPROGRESS;
		}
		final int[] exitStatus = new int[] { STATUS_OK };
		showStopper = new Thread(new Runnable() {
			@Override
			public void run() {
				Bundle systemBundle = context.getBundle(0);
				Bundle[] allBundles = context.getBundles();
				long start = System.currentTimeMillis();
				// stop the system bundle, this shuts down the OSGi framework
				// and all active bundles, bundle states: STOPPING -> RESOLVED
				try {
					systemBundle.stop();
				} catch (Exception e) {
					System.err.println("Poweroff: Couldn't stop system bundle: " + e.getMessage());
					exitStatus[0] = STATUS_FAILED;
					return;
				}

				// now wait at most timeout seconds for system bundle to stop
				while (System.currentTimeMillis() < start + timeout * 1000) {
					// check state of system bundle, when it reaches RESOLVED,
					// itself and all other bundles have been stopped
					if (systemBundle.getState() == Bundle.RESOLVED) {
						// success! all stopped, ready for shutdown
						return;
					}

					try {
						Thread.sleep(500);
					} catch (InterruptedException ignored) {
					}
				}

				// not stopped within timeout, notify
				StringBuffer out = new StringBuffer("Poweroff: OSGi still running after ");
				out.append(timeout).append(" seconds, active/stopping bundles:\n");
				for (Bundle bundle : allBundles) {
					if (bundle.getState() == Bundle.ACTIVE || bundle.getState() == Bundle.STOPPING) {
						out.append("\t'").append(bundle.getSymbolicName()).append("' ");
						out.append(stateToString(bundle.getState())).append("\n");
					}
				}
				out.append("Poweroff: No clean shutdown, check system/data integrity. Maybe increase timeout.");
				System.err.println(out.toString());
				exitStatus[0] = STATUS_TIMEOUTEXCEEDED;
			}
		}, "Poweroff: ShowStopper");

		// start the ShowStopper thread and wait for it to finish
		showStopper.setDaemon(true);
		showStopper.start();
		while (showStopper.isAlive()) {
			try {
				// FIXME: full timeout vs elapsed time since start
				showStopper.join(timeout * 1000);
			} catch (InterruptedException ignored) {
				// it's probably not proper to ignore these, shutdown hooks
				// shouldn't stall long; but since cleaning up is our very
				// intention here, we do it anyway
			}
		}

		System.out.println("Powering off. Bye.");
		return exitStatus[0];
	}

	public static boolean wasStarted() {
		// paranoia: a terminated showStopper had still been started
		return null != showStopper && (showStopper.isAlive() || showStopper.getState() == Thread.State.TERMINATED);
	}

	public static String stateToString(int state) {
		switch (state) {
		case Bundle.ACTIVE:
			return "ACTIVE";
		case Bundle.INSTALLED:
			return "INSTALLED";
		case Bundle.RESOLVED:
			return "RESOLVED";
		case Bundle.STARTING:
			return "STARTING";
		case Bundle.STOPPING:
			return "STOPPING";
		case Bundle.UNINSTALLED:
			return "UNINSTALLED";
		}
		return null;
	}
}
