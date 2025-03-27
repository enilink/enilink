package net.enilink.platform.workbench.commands;

import net.enilink.komma.common.CommonPlugin;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;
import net.enilink.komma.model.IModel;
import net.enilink.komma.model.IModelSet;
import net.enilink.komma.model.ModelUtil;
import net.enilink.komma.model.base.IURIMapRule;
import net.enilink.komma.model.base.IURIMapRuleSet;
import net.enilink.komma.model.base.SimpleURIMapRule;
import net.enilink.platform.core.UseService;
import net.enilink.platform.core.security.ISecureEntity;
import net.enilink.platform.core.security.SecurityUtil;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.IInputValidator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

public class UploadModelCommandHandler extends AbstractHandler {
	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		final Shell shell = HandlerUtil.getActiveShell(event);
		if (shell != null) {
			FileDialog fileDialog = new FileDialog(shell, SWT.TITLE);
			fileDialog.setText("Upload Model");
			// fileDialog.setAutoUpload(true);
			final String file = fileDialog.open();

			if (file != null) {
				return new UseService<IModelSet, String>(IModelSet.class) {
					@Override
					protected String withService(final IModelSet modelSet) {
						URI user = SecurityUtil.getUser();
						URI resourceUri = URIs.createFileURI(file);
						URI modelUri = resourceUri;

						InputStream in = null;
						try {
							in = modelSet.getURIConverter().createInputStream(modelUri);
							String ontology = ModelUtil.findOntology(new FileInputStream(file), modelUri.toString(), ModelUtil.mimeType(ModelUtil.contentDescription(modelSet.getURIConverter(), modelUri)));
							if (ontology != null) {
								modelUri = URIs.createURI(ontology);
							}

							// let the user choose a model uri
							InputDialog dialog = new InputDialog(shell, "Name the new model.", "Please enter an URI for your model.", modelUri.toString(),
								(IInputValidator) newText -> {
									URI newURI;
									try {
										newURI = URIs.createURI(newText);
									} catch (Exception e) {
										return "The given URI is invalid: " + e.getMessage();
									}
									if (newURI.isRelative()) {
										return "The URI must be absolute.";
									}
									IModel existing = modelSet.getModel(newURI, false);
									if (existing != null) {
										return "There already exists a model with the given URI.";
									}
									return null;
								});
							if (dialog.open() == Window.OK) {
								modelUri = URIs.createURI(dialog.getValue());
							} else {
								// user canceled the import
								return null;
							}
						} catch (Exception e) {
							// ontology could not be found for some reason
						} finally {
							if (in != null) {
								try {
									in.close();
								} catch (IOException e) {
									CommonPlugin.getPlugin().log(e);
								}
							}
						}

						IURIMapRuleSet mapRules = modelSet.getURIConverter().getURIMapRules();
						IURIMapRule mapRule = null;
						if (!modelUri.equals(resourceUri)) {
							mapRule = new SimpleURIMapRule(modelUri.toString(), resourceUri.toString());
						}
						try {
							// add a map rule if required, this should be the common case
							if (mapRule != null) {
								mapRules.addRule(mapRule);
							}
							IModel model = modelSet.createModel(modelUri);
							model.load(Collections.emptyMap());
							// set current user as owner
							((ISecureEntity)model).setAclOwner(user);
						} catch (IOException ioe) {
							MessageDialog.openError(shell, "Error loading model", "An error occurred while uploading the model: " + ioe.getMessage());
						} finally {
							// remove the optionally added map rule
							if (mapRule != null) {
								mapRules.removeRule(mapRule);
							}
						}
						return null;
					}
				}.getResult();
			}
		}
		return null;
	}
}
