package net.enilink.platform.workbench;

import net.enilink.commons.ui.editor.EditorForm;
import net.enilink.commons.ui.editor.IEditorPart;
import net.enilink.komma.edit.domain.IEditingDomainProvider;
import net.enilink.komma.edit.ui.views.AbstractEditingDomainPart;
import net.enilink.komma.edit.ui.views.SelectionProviderAdapter;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.part.ViewPart;

public abstract class AbstractModelView extends ViewPart {
	private AbstractEditingDomainPart editPart;

	private EditorForm editorForm;

	private IEditingDomainProvider editingDomainProvider;

	private final ISelectionProvider selectionProvider = new SelectionProviderAdapter();

	@Override
	public void createPartControl(Composite parent) {
		parent.setLayout(new FillLayout());
		editorForm = new EditorForm(parent) {
			@Override
			public void fireSelectionChanged(IEditorPart firingPart, final ISelection selection) {
				selectionProvider.setSelection(selection);
				super.fireSelectionChanged(firingPart, selection);
			}

			@Override
			public Object getAdapter(Class adapter) {
				if (IWorkbenchPartSite.class.equals(adapter) || IViewSite.class.equals(adapter)) {
					return getViewSite();
				}
				if (IToolBarManager.class.equals(adapter)) {
					return getViewSite().getActionBars().getToolBarManager();
				}
				if (IEditingDomainProvider.class.equals(adapter)) {
					return editingDomainProvider;
				}
				return null;
			}
		};
		editorForm.addPart(editPart);
		editPart.createContents(parent);
		getSite().setSelectionProvider(selectionProvider);
	}

	@Override
	public void dispose() {
		if (editorForm != null) {
			editorForm.dispose();
			editorForm = null;
		}
		super.dispose();
	}

	@Override
	public Object getAdapter(Class adapter) {
		if (IEditingDomainProvider.class.equals(adapter)) {
			return editingDomainProvider;
		}
		return super.getAdapter(adapter);
	}

	public AbstractEditingDomainPart getEditPart() {
		return editPart;
	}

	protected void setEditingDomainProvider(IEditingDomainProvider editingDomainProvider) {
		this.editingDomainProvider = editingDomainProvider;
		if (editPart != null) {
			editPart.setInput(editingDomainProvider != null ? editingDomainProvider.getEditingDomain().getModelSet() : null);
			editorForm.refreshStale();
		}
	}

	public void setEditPart(AbstractEditingDomainPart viewPart) {
		this.editPart = viewPart;
	}

	@Override
	public void setFocus() {
		editorForm.setFocus();
	}
}
