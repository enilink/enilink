package net.enilink.platform.core;

import java.io.IOException;
import java.util.Collections;

import org.aopalliance.intercept.MethodInvocation;
import net.enilink.composition.annotations.ParameterTypes;

import net.enilink.komma.model.IModel;
import net.enilink.komma.core.KommaModule;

public abstract class LazyModelSupport implements IModel {
	@ParameterTypes({})
	public synchronized KommaModule getModule(MethodInvocation invocation)
			throws Throwable {
		if (!isLoaded()) {
			try {
				load(Collections.emptyMap());
			} catch (IOException e) {
				// loading of model failed for some reason
				e.printStackTrace();
			}
		}
		return (KommaModule) invocation.proceed();
	}
}
