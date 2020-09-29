package net.enilink.platform.workbench.commands;

import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;
import net.enilink.komma.edit.ui.dialogs.InputDialog;
import net.enilink.komma.model.IModel;
import net.enilink.komma.model.IModelSet;
import net.enilink.platform.core.UseService;
import net.enilink.platform.core.security.ISecureEntity;
import net.enilink.platform.core.security.SecurityUtil;
import net.enilink.platform.workbench.EnilinkWorkbenchPlugin;
import net.enilink.platform.workbench.ModelEditorInput;
import net.enilink.vocab.owl.Ontology;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.IInputValidator;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;

public class CreateModelCommandHandler extends AbstractHandler {
	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		final Shell shell = HandlerUtil.getActiveShell(event);
		if (shell != null) {
			return new UseService<IModelSet, String>(IModelSet.class) {
				@SuppressWarnings("serial")
				@Override
				protected String withService(final IModelSet modelSet) {
					final boolean isAdmin = SecurityUtil.isMemberOf(modelSet.getMetaDataManager(), SecurityUtil.ADMINISTRATORS_GROUP);
					URI user = SecurityUtil.getUser();
					final URI modelNS = URIs.createURI("http://enilink.net/models/" + user.localPart() + "/");
					final URI[] newURI = new URI[1];
					// let the user choose a model uri
					InputDialog dialog = new InputDialog(shell, "Name the new model.", "Please enter a URI for your model.", isAdmin ? modelNS.toString() : "", new IInputValidator() {
						@Override
						public String isValid(String newText) {
							try {
								if (isAdmin) {
									newURI[0] = URIs.createURI(newText);
								} else {
									String[] segments = newText.split("[/]");
									for (int i = 0; i < segments.length; i++) {
										segments[i] = URIs.encodeSegment(segments[i], true);
									}
									newURI[0] = modelNS.trimSegments(1).appendSegments(segments);
									if (newText.endsWith("/")) {
										// add empty segment that is omitted by split
										newURI[0] = newURI[0].appendSegment("");
									}
								}
							} catch (Exception e) {
								return "The given URI is invalid: " + e.getMessage();
							}
							if (newURI[0].isRelative()) {
								return "The URI must be absolute.";
							}
							IModel existing = modelSet.getModel(newURI[0], false);
							if (existing != null) {
								return "There already exists a model with the given URI.";
							}
							return null;
						}
					}) {
						protected Text createText(Composite composite) {
							if (!isAdmin) {
								Composite group = new Composite(composite, SWT.NONE);
								group.setLayout(new GridLayout(2, false));
								group.setLayoutData(new GridData(SWT.FILL, SWT.DEFAULT, true, false));
								Label prefix = new Label(group, SWT.NONE);
								prefix.setText(modelNS.toString());
								return super.createText(group);
							} else {
								return super.createText(composite);
							}
						}
					};

					if (dialog.open() == Window.OK) {
						IModel model = modelSet.createModel(newURI[0]);
						model.setLoaded(true);
						// set current user as owner
						((ISecureEntity)model).setAclOwner(user);
						// ensure that at least one fact is contained in the
						// model
						model.getManager().assignTypes(model.getOntology(), Ontology.class);
						// open the recently created model
						try {
							IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
							page.openEditor(new ModelEditorInput(model), "net.enilink.platform.workbench.modelEditor", true, IWorkbenchPage.MATCH_INPUT);
						} catch (PartInitException e) {
							EnilinkWorkbenchPlugin.INSTANCE.log(e);
						}
					}
					return null;
				}
			}.getResult();
		}
		return null;
	}
}
