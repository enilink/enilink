package net.enilink.platform.core.edit;

import net.enilink.komma.common.AbstractKommaPlugin;
import net.enilink.komma.common.util.IResourceLocator;

public class EnilinkEditPlugin extends AbstractKommaPlugin {
	private static final EclipsePlugin osgiPlugin = new EclipsePlugin() {
	};

	public static final EnilinkEditPlugin INSTANCE = new EnilinkEditPlugin();

	public EnilinkEditPlugin() {
		super(new IResourceLocator[0]);
	}

	@Override
	public IResourceLocator getBundleResourceLocator() {
		return osgiPlugin;
	}
}
