package net.enilink.platform.core;

public abstract class PluginConfigModelSupport implements PluginConfigModel {
	@Override
	public void begin() {
		getModelSet().getUnitOfWork().begin();
	}

	@Override
	public void end() {
		getModelSet().getUnitOfWork().end();
	}
}
