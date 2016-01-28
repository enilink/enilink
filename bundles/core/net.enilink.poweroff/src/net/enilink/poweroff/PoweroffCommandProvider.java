package net.enilink.poweroff;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import org.eclipse.osgi.framework.console.CommandInterpreter;
import org.eclipse.osgi.framework.console.CommandProvider;
import org.osgi.framework.BundleContext;

/**
 * Proper poweroff command that stops the system bundle and exits the VM after
 * at most timeout seconds (defaults to 30s if not specified).
 * <p>
 * This is really a workaround for a bug introduced with the gogo-shell
 * conversion where 1) the gogo shell doesn't properly stop 2) close doesn't do
 * what it says.
 */
public class PoweroffCommandProvider implements CommandProvider {

	private BundleContext context;

	/**
	 * Called by OSGi-DS upon activation.
	 */
	protected void activate(BundleContext bundleContext) {
		context = bundleContext;
	}

	/**
	 * No-argument version of {@link #poweroff(int)} that defaults to 30s.
	 */
	// no @Description annotation to avoid felix dependency
	public void poweroff() {
		poweroff(30);
	}

	/**
	 * Handle the poweroff command. Ask the user for confirmation, then
	 * gracefully shutdown the OSGi framework (and exit forcefully when still
	 * running after timeout seconds).
	 * <p>
	 * NOTE: This is not the same as close, go see EquinoxCommandProvider for
	 * yourself.
	 */
	// no @Description annotation to avoid felix dependency
	public void poweroff(int timeout) {
		if (confirmStop()) {
			int status = Poweroff.poweroff(context, timeout);
			System.exit(status);
		}
	}

	@Override
	public String getHelp() {
		return "\tpoweroff [timeout] - Power off OSGi (shutdown and exit after at most timeout (default: 30) seconds).\n";
	}

	// docs say this should be found by reflection, but it appears the gogo
	// conversion has changed this as well
	// adding a @Description to poweroff would introduce a felix dependency
	public Object _help(CommandInterpreter intp) {
		String commandName = intp.nextArgument();
		if (!"poweroff".equals(commandName)) {
			return Boolean.FALSE;
		}
		String help = getHelp();
		if (!help.isEmpty()) {
			return help;
		}

		return Boolean.FALSE;
	}

	private boolean confirmStop() {
		System.out.print("Do you really want to exit? (Y/n)");
		System.out.flush();

		BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
		String reply = null;
		try {
			reply = reader.readLine();
		} catch (IOException e) {
			System.out.println("Error while reading confirmation");
		}

		if (reply != null) {
			if (reply.toLowerCase().startsWith("y") || reply.length() == 0) {
				return true;
			}
		}

		return false;
	}
}
