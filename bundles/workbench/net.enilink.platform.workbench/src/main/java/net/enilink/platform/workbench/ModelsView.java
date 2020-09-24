package net.enilink.platform.workbench;

import net.enilink.komma.edit.domain.IEditingDomainProvider;
import net.enilink.komma.model.IModelSet;
import org.eclipse.swt.widgets.Composite;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

public class ModelsView extends AbstractModelView {
	public static final String ID = "net.enilink.rap.workbench.modelsView";
	protected ServiceReference<IModelSet> modelSetRef;

	public ModelsView() {
		setEditPart(new ModelsPart());
	}

	@Override
	public void createPartControl(Composite parent) {
		super.createPartControl(parent);
		BundleContext ctx = FrameworkUtil.getBundle(getClass()).getBundleContext();
		modelSetRef = ctx.getServiceReference(IModelSet.class);
		if (modelSetRef != null) {
			IModelSet modelSet = ctx.getService(modelSetRef);
			if (modelSet != null) {
				setEditingDomainProvider((IEditingDomainProvider) modelSet.adapters().getAdapter(IEditingDomainProvider.class));
			}
		}
	}

	@Override
	public void dispose() {
		if (modelSetRef != null) {
			FrameworkUtil.getBundle(getClass()).getBundleContext().ungetService(modelSetRef);
			modelSetRef = null;
		}
		super.dispose();
	}
}