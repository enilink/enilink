/*******************************************************************************
 * Copyright (c) 2010, 2012 EclipseSource and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * EclipseSource - initial API and implementation
 * Fraunhofer IWU - adaption for eniLINK
 ******************************************************************************/
package net.enilink.platform.internal.workbench;

import org.eclipse.rap.rwt.RWT;
import org.eclipse.rap.rwt.application.EntryPoint;

import java.lang.reflect.Method;

public class SnippetRunner implements EntryPoint {

	private static final String PACKAGE_PREFIX = "net.enilink.platform.workbench.snippets";

	public int createUI() {
		String value = RWT.getRequest().getParameter("class");
		if (value == null || value.length() <= 0) {
			String message = "No class given in URL parameter \"class\"";
			throw new IllegalArgumentException(message);
		}
		Class<?> snippetClass = findClass(value);
		runSnippet(snippetClass);
		return 0;
	}

	private Class<?> findClass(String value) {
		if (value.indexOf('.') != -1) {
			return loadClass(value);
		}
		return loadClass(PACKAGE_PREFIX + "." + value);
	}

	private Class<?> loadClass(String className) {
		ClassLoader classLoader = SnippetRunner.class.getClassLoader();
		try {
			return classLoader.loadClass(className);
		} catch (ClassNotFoundException e) {
			String message = "The class could not be found: " + className;
			throw new RuntimeException(message, e);
		}
	}

	private void runSnippet(Class<?> snippetClass) {
		if (EntryPoint.class.isAssignableFrom(snippetClass)) {
			EntryPoint entrypoint = (EntryPoint) createInstance(snippetClass);
			entrypoint.createUI();
		} else {
			Method method = getMainMethod(snippetClass);
			if (method == null) {
				String message = "The class " + snippetClass.getName() + " does not implement EntryPoint" + " and does not have a main method.";
				throw new IllegalArgumentException(message);
			}
			invoke(method);
		}
	}

	private Object createInstance(Class<?> classToRun) {
		try {
			return classToRun.getDeclaredConstructor().newInstance();
		} catch (IllegalAccessException e) {
			String message = "The class or constructor is not accessible: " + classToRun.getName();
			throw new RuntimeException(message, e);
		} catch (Exception e) {
			String message = "The class could not be instantiated: " + classToRun.getName();
			throw new RuntimeException(message, e);
		}
	}

	private Method getMainMethod(Class<?> snippetClass) {
		Method method = null;
		try {
			Class<?>[] mainParameterTypes = new Class[]{String[].class};
			method = snippetClass.getMethod("main", mainParameterTypes);
		} catch (NoSuchMethodException e) {
			// no such method, return null
		}
		return method;
	}

	private void invoke(Method method) {
		try {
			method.invoke(null, new Object[]{new String[0]});
		} catch (Exception exception) {
			throw new RuntimeException("Could not invoke method " + method.getName(), exception);
		}
	}

}