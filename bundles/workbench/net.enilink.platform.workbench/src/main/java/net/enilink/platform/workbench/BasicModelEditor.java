package net.enilink.platform.workbench;

import net.enilink.commons.ui.editor.EditorForm;
import net.enilink.commons.ui.editor.IEditorPart;
import net.enilink.komma.common.util.IResourceLocator;
import net.enilink.komma.core.IValue;
import net.enilink.komma.edit.domain.IEditingDomainProvider;
import net.enilink.komma.edit.ui.editor.KommaMultiPageEditor;
import net.enilink.komma.edit.ui.editor.KommaMultiPageEditorSupport;
import net.enilink.komma.edit.ui.views.IViewerMenuSupport;
import net.enilink.komma.edit.ui.views.SelectionProviderAdapter;
import net.enilink.komma.model.IModel;
import net.enilink.komma.model.IModelSet;
import net.enilink.komma.owl.editor.classes.ClassesPart;
import net.enilink.komma.owl.editor.ontology.OntologyPart;
import net.enilink.komma.owl.editor.properties.DatatypePropertiesPart;
import net.enilink.komma.owl.editor.properties.ObjectPropertiesPart;
import net.enilink.komma.owl.editor.properties.OtherPropertiesPart;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IWorkbenchPartSite;

/**
 * This is the basic eniLINK editor.
 */
public class BasicModelEditor extends KommaMultiPageEditor implements IViewerMenuSupport {
	protected EditorForm form;
	protected SelectionProviderAdapter formSelectionProvider = new SelectionProviderAdapter();

	/**
	 * This creates a model editor.
	 */
	public BasicModelEditor() {
		super();
	}

	class BasicEditorSupport extends KommaMultiPageEditorSupport<BasicModelEditor> {
		public BasicEditorSupport() {
			super(BasicModelEditor.this);
			disposeModelSet = false;
		}

		@Override
		protected IResourceLocator getResourceLocator() {
			return EnilinkWorkbenchPlugin.INSTANCE;
		}

		@Override
		protected IModelSet createModelSet() {
			ModelEditorInput input = (ModelEditorInput) editor.getEditorInput();
			return input.getModel().getModelSet();
		}

		@Override
		public void createModel() {
			ModelEditorInput input = (ModelEditorInput) editor.getEditorInput();
			model = input.getModel();
		}

		// @Override
		// protected AdapterFactoryEditingDomain getExistingEditingDomain(
		// IModelSet modelSet) {
		// // force the creation of a new editing domain for this editor
		// return null;
		// }

		@Override
		public void handlePageChange(Object activeEditor) {
			super.handlePageChange(activeEditor);
			editorSelectionProvider.setSelectionProvider(formSelectionProvider);
		}

		@Override
		public Object getAdapter(Class key) {
			if (IModel.class.equals(key)) {
				return model;
			}
			return super.getAdapter(key);
		}
	}

	@Override
	protected KommaMultiPageEditorSupport<? extends KommaMultiPageEditor> createEditorSupport() {
		return new BasicEditorSupport();
	}

	protected void addPage(String label, IEditorPart editPart) {
		Composite control = form.getWidgetFactory().createComposite(
				form.getBody());
		control.setLayout(new FillLayout());
		control.setData("editPart", editPart);

		editPart.initialize(form);
		editPart.createContents(control);
		editPart.setInput(getEditorSupport().getModel());
		editPart.refresh();
		setPageText(addPage(control), label);
	}

	@Override
	public void createPages() {
		final boolean[] internalChange = {false};
		form = new EditorForm(getContainer()) {
			@Override
			public Object getAdapter(Class adapter) {
				if (IEditingDomainProvider.class.equals(adapter)) {
					return BasicModelEditor.this;
				} else if (IViewerMenuSupport.class.equals(adapter)) {
					return BasicModelEditor.this;
				}
				return super.getAdapter(adapter);
			}

			@Override
			public void fireSelectionChanged(IEditorPart firingPart,
											 ISelection selection) {
				try {
					internalChange[0] = true;
					formSelectionProvider.setSelection(selection);
				} finally {
					internalChange[0] = false;
				}
			}
		};
		formSelectionProvider
				.addSelectionChangedListener(event -> {
					if (internalChange[0]) {
						return;
					}
					Object selected = ((IStructuredSelection) event
							.getSelection()).getFirstElement();
					// allow arbitrary selections to be adapted to IValue
					// objects
					if (selected != null && !(selected instanceof IValue)) {
						Object adapter = Platform.getAdapterManager()
								.getAdapter(selected, IValue.class);
						if (adapter != null) {
							selected = adapter;
						}
					}
					if (selected != null) {
						IEditorPart editPart = (IEditorPart) getControl(
								getActivePage()).getData("editPart");
						if (editPart != null
								&& editPart.setEditorInput(selected)) {
							form.refreshStale();
						}
					}
				});

		// Creates the model from the editor input
		getEditorSupport().createModel();

		//addPage(new Composite(getContainer(), SWT.NONE));
		addPage("Ontology", new OntologyPart());
		addPage("Classes", new ClassesPart());
		addPage("ObjectProperties", new ObjectPropertiesPart());
		addPage("DatatypeProperties", new DatatypePropertiesPart());
		addPage("other Properties", new OtherPropertiesPart());
		setPageText(0, getEditorSupport().getString("_UI_SelectionPage_label"));

		getSite().getShell().getDisplay().asyncExec(() -> setActivePage(0));

		// Ensures that this editor will only display the page's tab
		// area if there are more than one page
		getContainer().addControlListener(new ControlAdapter() {
			boolean guard = false;

			@Override
			public void controlResized(ControlEvent event) {
				if (!guard) {
					guard = true;
					getEditorSupport().hideTabs();
					guard = false;
				}
			}
		});

		getSite().getShell().getDisplay().asyncExec(() -> getEditorSupport().updateProblemIndication());
	}

	@Override
	public void createContextMenuFor(StructuredViewer viewer, Control menuParent, IWorkbenchPartSite partSite) {
		getEditorSupport().createContextMenuFor(viewer, menuParent, partSite);
	}

	@Override
	public boolean isDirty() {
		// ignore any changes to dirty state
		return false;
	}
}
