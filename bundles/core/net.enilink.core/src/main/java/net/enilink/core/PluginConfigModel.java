package net.enilink.core;

import net.enilink.komma.model.IModel;

/**
 * The service interface for RDF models that contain configuration data of
 * enilink plugins. This interface is exposed as OSGi service and can be
 * consumed by clients through standard OSGi methods.
 */
public interface PluginConfigModel extends IModel {
	/**
	 * Begin a unit-of-work with this config model.
	 */
	void begin();

	/**
	 * End a unit-of-work with this config model.
	 */
	void end();
}
