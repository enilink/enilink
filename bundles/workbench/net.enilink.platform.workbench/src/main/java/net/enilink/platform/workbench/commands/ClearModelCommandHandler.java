package net.enilink.platform.workbench.commands;

import net.enilink.komma.dm.change.IDataChangeSupport;
import net.enilink.komma.model.IModel;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;

public class ClearModelCommandHandler extends AbstractHandler {
	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IWorkbenchPart part = HandlerUtil.getActiveEditor(event);
		IModel model = part != null ? part.getAdapter(IModel.class) : null;
		if (model != null) {
			if (MessageDialog.openConfirm(part.getSite().getShell(), "Clear model data", "Are you sure to delete all data of model '" + model.getURI() + "'?")) {
				IDataChangeSupport changeSupport = model.getModelSet().getDataChangeSupport();
				try {
					changeSupport.setEnabled(null, false);
					model.getManager().clear();
				} finally {
					changeSupport.setEnabled(null, true);
				}
				MessageDialog.openInformation(part.getSite().getShell(), "Model data cleared", "All data of model '" + model.getURI() + "' was successfully removed.");
			}
		}
		return null;
	}
}
