package net.enilink.ldp.sail;

import java.security.PrivilegedAction;
import java.util.List;
import java.util.Set;

import javax.security.auth.Subject;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;

import net.enilink.core.security.SecurityUtil;
import net.enilink.komma.core.IQuery;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;
import net.enilink.komma.em.concepts.IResource;
import net.enilink.komma.model.IModel;
import net.enilink.komma.model.IModelSet;

public class Activator implements BundleActivator {

	private static BundleContext context;

	private ServiceTracker<IModelSet, IModelSet> modelSetTracker;
	private static IModelSet modelSet;

	public static BundleContext getContext() {
		return context;
	}

	public static IModelSet getModelSet() {
		return modelSet;
	}

	@Override
	public void start(BundleContext bundleContext) throws Exception {
		context = bundleContext;
		try {
			// the LDP cache instance will get initialized from the federation,
			// which is used as the main repository for the model set
			// the LDP cache instance can therefore not access the modelset
			// right away but keeps track of the endpoints itself
			modelSetTracker = new ServiceTracker<IModelSet, IModelSet>(bundleContext, IModelSet.class, null) {
				@Override
				public IModelSet addingService(final ServiceReference<IModelSet> reference) {
					return Subject.doAs(SecurityUtil.SYSTEM_USER_SUBJECT, new PrivilegedAction<IModelSet>() {
						@Override
						public IModelSet run() {
							modelSet = getContext().getService(reference);
							modelSet.getUnitOfWork().begin();
							try {
								Set<org.openrdf.model.URI> newEndpoints = LdpCache.getInstance().getEndpoints();
								URI EP_MODEL_URI = URIs.createURI(LdpCache.ENDPOINT_MODEL_URI.toString());
								IModel endpointModel = modelSet.getModel(EP_MODEL_URI, false);
								if (null == endpointModel) {
									endpointModel = modelSet.createModel(EP_MODEL_URI);
									((IResource) endpointModel).setRdfsLabel("registered LDP endpoints");
								} else {
									// sync endpoints between map and model
									// query the model for known endpoints
									IQuery<?> query = endpointModel.getManager()
											.createQuery("PREFIX le: <" + LdpCache.ENDPOINT_MODEL_URI + "#> " //
													+ "SELECT ?epAddr WHERE {" //
													+ " ?ep a le:Endpoint . " //
													+ " ?ep le:hasAddress ?epAddr . " //
													+ "}", false);
									List<IReference> epAddrs = query.evaluateRestricted(IReference.class).toList();
									// register the old endpoints from the model
									for (IReference endpointAddress : epAddrs) {
										LdpCache.getInstance().addEndpoint(endpointAddress);
									}
								}
								// put the new endpoints into the model
								for (org.openrdf.model.URI newEndpoint : newEndpoints) {
									LdpCache.getInstance().addEndpoint(URIs.createURI(newEndpoint.toString()));
								}

							} catch (Throwable t) {
								t.printStackTrace();
								throw t;
							} finally {
								modelSet.getUnitOfWork().end();
							}
							return modelSet;
						}
					});
				}
			};
			modelSetTracker.open();

		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	@Override
	public void stop(BundleContext bundleContext) throws Exception {
		if (modelSetTracker != null) {
			modelSetTracker.close();
			modelSetTracker = null;
		}
		modelSet = null;
		context = null;
	}
}
