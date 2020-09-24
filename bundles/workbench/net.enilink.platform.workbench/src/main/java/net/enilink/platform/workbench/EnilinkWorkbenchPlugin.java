package net.enilink.platform.workbench;

import net.enilink.komma.common.AbstractKommaPlugin;
import net.enilink.komma.common.ui.EclipseUIPlugin;
import net.enilink.komma.common.util.IResourceLocator;
import org.osgi.framework.BundleContext;

public final class EnilinkWorkbenchPlugin extends AbstractKommaPlugin {
	public static final EnilinkWorkbenchPlugin INSTANCE = new EnilinkWorkbenchPlugin();

	private static Implementation plugin;

	public EnilinkWorkbenchPlugin() {
		super(new IResourceLocator[]{});
	}

	@Override
	public IResourceLocator getBundleResourceLocator() {
		return plugin;
	}

	public static Implementation getPlugin() {
		return plugin;
	}

	public static class Implementation extends EclipseUIPlugin {
		public Implementation() {
			super();

			// Remember the static instance.
			plugin = this;
		}

		@Override
		public void start(BundleContext context) throws Exception {
			super.start(context);
		}
	}

}
