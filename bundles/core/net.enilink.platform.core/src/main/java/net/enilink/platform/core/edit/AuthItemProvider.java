package net.enilink.platform.core.edit;

import net.enilink.komma.common.adapter.IAdapterFactory;
import net.enilink.komma.common.util.IResourceLocator;
import net.enilink.komma.core.IEntity;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.URI;
import net.enilink.komma.edit.provider.IViewerNotification;
import net.enilink.komma.edit.provider.ReflectiveItemProvider;
import net.enilink.komma.edit.provider.ViewerNotification;
import net.enilink.komma.em.concepts.IResource;
import net.enilink.komma.model.event.IStatementNotification;
import net.enilink.platform.core.security.SecurityUtil;

import java.util.Collection;

public class AuthItemProvider extends ReflectiveItemProvider {
	public AuthItemProvider(IAdapterFactory adapterFactory, IResourceLocator resourceLocator,
							Collection<? extends IReference> targetTypes) {
		super(adapterFactory, resourceLocator, targetTypes);
	}

	@Override
	protected void addViewerNotifications(Collection<IViewerNotification> viewerNotifications,
			IStatementNotification notification) {
		IEntity object = resolveReference(notification.getSubject());
		if (object instanceof IResource) {
			((IResource) object).refresh(notification.getPredicate());
			viewerNotifications.add(new ViewerNotification(object, true, true));
		}
	}

	@Override
	public String getText(Object object) {
		URI uri = ((IReference)object).getURI();
		if (uri != null && uri.toString().startsWith("enilink:")) {
			return uri.toString().replaceFirst("enilink:", "");
		}
		return super.getText(object);
	}
}