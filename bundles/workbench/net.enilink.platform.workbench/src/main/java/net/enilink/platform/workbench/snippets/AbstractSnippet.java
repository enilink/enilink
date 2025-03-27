package net.enilink.platform.workbench.snippets;

import net.enilink.commons.ui.editor.EditorForm;
import net.enilink.commons.ui.editor.IEditorPart;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.URIs;
import net.enilink.komma.edit.domain.IEditingDomainProvider;
import net.enilink.komma.edit.ui.action.CreateChildrenActionContributor;
import net.enilink.komma.edit.ui.dnd.EditingDomainViewerDropAdapter;
import net.enilink.komma.edit.ui.dnd.LocalTransfer;
import net.enilink.komma.edit.ui.dnd.ViewerDragAdapter;
import net.enilink.komma.edit.ui.views.IViewerMenuSupport;
import net.enilink.komma.model.IModel;
import net.enilink.komma.model.IModelSet;

import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.rap.rwt.RWT;
import org.eclipse.rap.rwt.application.AbstractEntryPoint;
import org.eclipse.rap.rwt.client.service.JavaScriptExecutor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DragSource;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.ui.IWorkbenchPartSite;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

public abstract class AbstractSnippet extends AbstractEntryPoint implements IViewerMenuSupport {
	private IEditingDomainProvider editingDomainProvider;
	private EditorForm editorForm;
	private final CreateChildrenActionContributor createChildActionContributor = new CreateChildrenActionContributor();
	protected ServiceReference<IModelSet> modelSetRef;

	protected IModelSet getModelSet() {
		BundleContext ctx = FrameworkUtil.getBundle(getClass()).getBundleContext();
		modelSetRef = ctx.getServiceReference(IModelSet.class);
		if (modelSetRef != null) {
			return ctx.getService(modelSetRef);
		}
		return null;
	}

	@Override
	protected void createContents(Composite parent) {
		IModelSet modelSet = getModelSet();
		if (modelSet == null) {
			return;
		}
		parent.getDisplay().disposeExec(new Runnable() {
			@Override
			public void run() {
				// dipose model set reference
				FrameworkUtil.getBundle(getClass()).getBundleContext().ungetService(modelSetRef);
			}
		});
		parent.setLayout(new FillLayout());
		editingDomainProvider = (IEditingDomainProvider) modelSet.adapters().getAdapter(IEditingDomainProvider.class);
		editorForm = new EditorForm(parent) {
			@Override
			public void fireSelectionChanged(IEditorPart firingPart, final ISelection selection) {
				if (selection instanceof IStructuredSelection) {
					Object first = ((IStructuredSelection) selection).getFirstElement();
					if (first instanceof IReference) {
						// use HTML5 postMessage to publish selection event
						RWT.getClient().getService(JavaScriptExecutor.class).execute("(window.parent || window).postMessage(\"{ selection : '" + first + "' }\", '*')");
					}
				}
				createChildActionContributor.selectionChanged(null, editingDomainProvider.getEditingDomain(), selection);
				super.fireSelectionChanged(firingPart, selection);
			}

			@Override
			public Object getAdapter(Class adapter) {
				if (IEditingDomainProvider.class.equals(adapter)) {
					return editingDomainProvider;
				} else if (IViewerMenuSupport.class.equals(adapter)) {
					return AbstractSnippet.this;
				}
				return null;
			}
		};
		final IEditorPart editPart = createEditorPart();
		editorForm.addPart(editPart);
		editPart.createContents(parent);

		String modelUri = RWT.getRequest().getParameter("model");
		if (modelUri != null) {
			try {
				IModel model = modelSet.getModel(URIs.createURI(modelUri), false);
				if (model != null) {
					editPart.setInput(model);
					editorForm.refreshStale();
				}
			} catch (IllegalArgumentException e) {
				// ignore
			}
		}

		// required for correct cleanup
		parent.getDisplay().addListener(SWT.Dispose, (Listener) event -> editorForm.dispose());
	}

	abstract IEditorPart createEditorPart();

	public void createContextMenuFor(StructuredViewer viewer, Control menuParent, IWorkbenchPartSite partSite) {
		Menu menu = viewer.getControl().getMenu();
		if (menu != null && !menu.isDisposed()) {
			menu.dispose();
			viewer.getControl().setMenu(null);
		}

		MenuManager contextMenu = new MenuManager("#PopUp");
		contextMenu.setRemoveAllWhenShown(true);
		contextMenu.addMenuListener((IMenuListener) manager -> {
			manager.add(new Separator("additions"));
			createChildActionContributor.menuAboutToShow(manager, "additions");
		});
		menu = contextMenu.createContextMenu(viewer.getControl());
		viewer.getControl().setMenu(menu);

		int dndOperations = DND.DROP_COPY | DND.DROP_MOVE | DND.DROP_LINK;
		Transfer[] transfers = new Transfer[]{LocalTransfer.getInstance()};

		DragSource source = (DragSource) viewer.getControl().getData(DND.DRAG_SOURCE_KEY);
		if (source != null && !source.isDisposed()) {
			source.dispose();
		}
		viewer.addDragSupport(dndOperations, transfers, new ViewerDragAdapter(viewer));

		DropTarget target = (DropTarget) viewer.getControl().getData(DND.DROP_TARGET_KEY);
		if (target != null && !target.isDisposed()) {
			target.dispose();
		}
		viewer.addDropSupport(dndOperations, transfers, new EditingDomainViewerDropAdapter(editingDomainProvider.getEditingDomain(), viewer));
	}
}
