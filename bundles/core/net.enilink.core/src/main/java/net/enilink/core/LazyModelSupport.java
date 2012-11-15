package net.enilink.core;

import java.io.IOException;
import java.util.Collections;

import net.enilink.composition.annotations.ParameterTypes;
import net.enilink.composition.concepts.Message;

import net.enilink.komma.model.IModel;

public abstract class LazyModelSupport implements IModel {
	@ParameterTypes({})
	public synchronized void getModule(Message msg) {
		if (!isLoaded()) {
			try {
				load(Collections.emptyMap());
			} catch (IOException e) {
				// loading of model failed for some reason
				e.printStackTrace();
			}
		}
		msg.proceed();
	}
}
