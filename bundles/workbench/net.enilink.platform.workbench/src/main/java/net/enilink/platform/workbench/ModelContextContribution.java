package net.enilink.platform.workbench;

import net.enilink.komma.model.IModel;
import org.eclipse.rap.rwt.RWT;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.menus.WorkbenchWindowControlContribution;

public class ModelContextContribution extends WorkbenchWindowControlContribution {
	@Override
	protected Control createControl(final Composite parent) {
		Composite labelComposite = new Composite(parent, SWT.NONE);
		FillLayout layout = new FillLayout();
		layout.marginHeight = 5;
		labelComposite.setLayout(layout);
		final Label l = new Label(labelComposite, SWT.LEFT);
		l.setData(RWT.CUSTOM_VARIANT, "modelContext");

		getWorkbenchWindow().getPartService().addPartListener(new IPartListener2() {
			private IModel getModel(IWorkbenchPartReference partRef) {
				IWorkbenchPart part = partRef.getPart(true);
				// use only models of editors as context
				return part instanceof IEditorPart ? part.getAdapter(IModel.class) : null;
			}

			@Override
			public void partActivated(IWorkbenchPartReference partRef) {
				IModel model = getModel(partRef);
				if (model != null) {
					l.setText(model.getURI().toString());
					parent.layout(true);
				}
			}

			@Override
			public void partBroughtToTop(IWorkbenchPartReference partRef) {
			}

			@Override
			public void partClosed(IWorkbenchPartReference partRef) {
				IModel model = getModel(partRef);
				if (model != null) {
					l.setText("");
					parent.layout(true);
				}
			}

			@Override
			public void partDeactivated(IWorkbenchPartReference partRef) {
			}

			@Override
			public void partHidden(IWorkbenchPartReference partRef) {
			}

			@Override
			public void partInputChanged(IWorkbenchPartReference partRef) {
			}

			@Override
			public void partOpened(IWorkbenchPartReference partRef) {
			}

			@Override
			public void partVisible(IWorkbenchPartReference partRef) {
			}
		});
		return labelComposite;
	}
}
