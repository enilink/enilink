package net.enilink.platform.workbench.commands;

import net.enilink.komma.dm.change.IDataChangeSupport;
import net.enilink.komma.model.IModel;
import net.enilink.komma.model.IModelSet;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

public class DeleteModelCommandHandler extends AbstractHandler {
	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		ISelection selection = HandlerUtil.getCurrentSelection(event);
		if (selection instanceof IStructuredSelection) {
			Object element = ((IStructuredSelection) selection).getFirstElement();
			if (element instanceof IModel) {
				IModel model = (IModel) element;
				if (model != null) {
					Shell shell = HandlerUtil.getActiveShell(event);
					if (MessageDialog.openConfirm(shell, "Delete model", "Are you sure to delete model '" + model.getURI() + "'?")) {
						IModelSet modelSet = model.getModelSet();
						IDataChangeSupport changeSupport = model.getModelSet().getDataChangeSupport();
						try {
							changeSupport.setEnabled(null, false);
							model.getManager().clear();
						} finally {
							changeSupport.setEnabled(null, true);
						}
						model.unload();
						modelSet.getModels().remove(model);
						modelSet.getMetaDataManager().remove(model);
						MessageDialog.openInformation(shell, "Model deleted", "Model '" + model.getURI() + "' was successfully removed.");
					}
				}
			}
		}
		return null;
	}
}
