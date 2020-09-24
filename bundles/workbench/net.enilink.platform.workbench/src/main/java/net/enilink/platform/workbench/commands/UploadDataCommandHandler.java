package net.enilink.platform.workbench.commands;

import net.enilink.komma.common.CommonPlugin;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;
import net.enilink.komma.model.IModel;
import net.enilink.komma.model.IModelSet;
import net.enilink.komma.model.ModelUtil;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.content.IContentDescription;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class UploadDataCommandHandler extends AbstractHandler {
	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IWorkbenchPart part = HandlerUtil.getActiveEditor(event);
		IModel model = part != null ? part.getAdapter(IModel.class) : null;
		if (model != null) {
			Shell shell = part.getSite().getShell();
			FileDialog fileDialog = new FileDialog(shell, SWT.TITLE);
			fileDialog.setText("Upload data to <" + model + ">");
			String file = fileDialog.open();
			if (file != null) {
				final IModelSet modelSet = model.getModelSet();
				URI resourceUri = URIs.createFileURI(file);
				InputStream in = null;
				try {
					in = modelSet.getURIConverter().createInputStream(resourceUri);
					IContentDescription contentDescription = ModelUtil.determineContentDescription(resourceUri, modelSet.getURIConverter(), null);
					Map<Object, Object> options = new HashMap<Object, Object>();
					options.put(IContentDescription.class, contentDescription);
					model.load(in, options);
					// refresh the model
					model.unloadManager();
				} catch (Exception e) {
					MessageDialog.openError(shell, "Import error.", "Importing data failed:\n" + e.getMessage());
				} finally {
					if (in != null) {
						try {
							in.close();
						} catch (IOException e) {
							CommonPlugin.getPlugin().log(e);
						}
					}
				}
			}
		}
		return null;
	}
}
