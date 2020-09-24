package net.enilink.platform.workbench;

import net.enilink.komma.model.IModel;
import net.enilink.komma.model.ModelUtil;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IPersistableElement;

public class ModelEditorInput implements IEditorInput {
	private final IModel model;

	public ModelEditorInput(IModel model) {
		this.model = model;
	}

	public IModel getModel() {
		return model;
	}

	@SuppressWarnings("rawtypes")
	@Override
	public Object getAdapter(Class adapter) {
		return null;
	}

	@Override
	public boolean exists() {
		return true;
	}

	@Override
	public ImageDescriptor getImageDescriptor() {
		return ImageDescriptor.getMissingImageDescriptor();
	}

	@Override
	public String getName() {
		return ModelUtil.getLabel(model);
	}

	@Override
	public IPersistableElement getPersistable() {
		return null;
	}

	@Override
	public String getToolTipText() {
		return ModelUtil.getLabel(model);
	}
}
