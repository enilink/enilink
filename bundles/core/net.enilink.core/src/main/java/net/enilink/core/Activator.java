package net.enilink.core;

import java.util.Dictionary;
import java.util.Hashtable;

import net.enilink.composition.mappers.DefaultRoleMapper;
import net.enilink.komma.model.IModelSet;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Activator implements BundleActivator {
	private static Logger logger = LoggerFactory
			.getLogger(DefaultRoleMapper.class);

	private static BundleContext context;

	private ServiceRegistration<?> modelSetReg;

	public static BundleContext getContext() {
		return context;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.osgi.framework.BundleActivator#start(org.osgi.framework.BundleContext
	 * )
	 */
	public void start(BundleContext bundleContext) throws Exception {
		Activator.context = bundleContext;
		Dictionary<String, Object> props = new Hashtable<>();
		modelSetReg = bundleContext.registerService(IModelSet.class.getName(),
				new ServiceFactory<IModelSet>() {
					@Override
					public IModelSet getService(Bundle bundle,
							ServiceRegistration<IModelSet> registration) {
						try {
							return ModelSetManager.INSTANCE.getModelSet();
						} catch (Exception e) {
							logger.error("Unable to create model set", e);
						}
						return null;
					}

					@Override
					public void ungetService(Bundle bundle,
							ServiceRegistration<IModelSet> registration,
							IModelSet service) {
					}
				}, props);

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.osgi.framework.BundleActivator#stop(org.osgi.framework.BundleContext)
	 */
	public void stop(BundleContext bundleContext) throws Exception {
		if (modelSetReg != null) {
			modelSetReg.unregister();
			modelSetReg = null;
		}
		ModelSetManager.INSTANCE.shutdown();
		Activator.context = null;
	}

}
