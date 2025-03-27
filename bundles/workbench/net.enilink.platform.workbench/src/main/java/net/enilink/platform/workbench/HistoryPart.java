package net.enilink.platform.workbench;

import net.enilink.komma.common.adapter.IAdapterFactory;
import net.enilink.komma.core.IEntity;
import net.enilink.komma.core.IQuery;
import net.enilink.komma.core.URIs;
import net.enilink.komma.edit.ui.properties.IEditUIPropertiesImages;
import net.enilink.komma.edit.ui.properties.KommaEditUIPropertiesPlugin;
import net.enilink.komma.edit.ui.provider.AdapterFactoryLabelProvider;
import net.enilink.komma.edit.ui.provider.ExtendedImageRegistry;
import net.enilink.komma.edit.ui.views.AbstractEditingDomainPart;
import net.enilink.komma.em.util.ISparqlConstants;
import net.enilink.komma.model.IModel;
import net.enilink.platform.core.security.SecurityUtil;
import net.enilink.platform.workbench.model.ChangeDescription;
import net.enilink.vocab.komma.KOMMA;
import net.enilink.vocab.rdf.Statement;
import java.security.PrivilegedAction;
import java.text.DateFormat;
import java.util.stream.Stream;

import javax.security.auth.Subject;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.Tree;

public class HistoryPart extends AbstractEditingDomainPart {
	private IAdapterFactory adapterFactory;
	private TreeViewer viewer;
	private IModel auditModel;

	static class ChangeElement {
		Statement stmt;
		boolean added;

		ChangeElement(Statement stmt, boolean added) {
			this.stmt = stmt;
			this.added = added;
		}
	}

	@Override
	public void createContents(Composite parent) {
		parent.setLayout(new FillLayout());

		final Tree tree = getWidgetFactory().createTree(parent,
				SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION);
		tree.setHeaderVisible(true);
		tree.setLinesVisible(true);

		viewer = new TreeViewer(tree);
		viewer.setUseHashlookup(true);

		TreeViewerColumn dateColumn = new TreeViewerColumn(viewer, SWT.LEFT);
		dateColumn.getColumn().setText("Date");
		dateColumn.getColumn().setWidth(300);

		TreeViewerColumn agentColumn = new TreeViewerColumn(viewer, SWT.LEFT);
		agentColumn.getColumn().setText("Details");
		agentColumn.getColumn().setWidth(600);

		viewer.setContentProvider(new ITreeContentProvider() {
			@Override
			public Object[] getElements(Object inputElement) {
				if (inputElement instanceof IModel) {
					IQuery<?> query = ((IModel) inputElement).getManager().createQuery(
							ISparqlConstants.PREFIX +
									"prefix dcterms: <http://purl.org/dc/terms/> " +
									"select ?change { ?change a ?changeDescription ; dcterms:date ?date } order by desc(?date) limit 10");
					query.setParameter("changeDescription", KOMMA.NAMESPACE_URI.appendLocalPart("ChangeDescription"));
					return query.evaluate().toList().toArray();
				}
				return new Object[0];
			}

			@Override
			public Object[] getChildren(Object parentElement) {
				if (parentElement instanceof ChangeDescription) {
					Stream<ChangeElement> removed = ((ChangeDescription) parentElement).getRemoved().stream()
							.map(stmt -> new ChangeElement(stmt, false));
					Stream<ChangeElement> added = ((ChangeDescription) parentElement).getAdded().stream()
							.map(stmt -> new ChangeElement(stmt, true));
					return Stream.concat(removed, added).toArray(ChangeElement[]::new);
				}
				return new Object[0];
			}

			@Override
			public Object getParent(Object element) {
				return null;
			}

			@Override
			public boolean hasChildren(Object element) {
				return element instanceof ChangeDescription;
			}

			@Override
			public void dispose() {
			}

			@Override
			public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
			}
		});
		createActions(parent);
	}

	public void createActions(Composite parent) {
		IToolBarManager toolBarManager = getForm().getAdapter(IToolBarManager.class);
		ToolBarManager ownManager = null;
		if (toolBarManager == null) {
			toolBarManager = ownManager = new ToolBarManager(SWT.HORIZONTAL);
			ToolBar toolBar = ownManager.createControl(parent);
			getWidgetFactory().adapt(toolBar);
			toolBar.setLayoutData(new GridData(SWT.RIGHT, SWT.DEFAULT, true,
					false));
		}

		IAction refreshAction = new Action("Refresh") {
			@Override
			public void run() {
				refresh();
			}
		};
		refreshAction.setImageDescriptor(ExtendedImageRegistry.getInstance()
				.getImageDescriptor(
						KommaEditUIPropertiesPlugin.INSTANCE
								.getImage(IEditUIPropertiesImages.REFRESH)));
		toolBarManager.add(refreshAction);

		if (ownManager != null) {
			ownManager.update(true);
		}
	}

	@Override
	public void setInput(Object input) {
		if (input instanceof IModel) {
			setEditorInput(input);
		}
	}

	@Override
	public boolean setEditorInput(Object input) {
		if (input instanceof IModel || input == null) {
			if (input == null) {
				auditModel = null;
			} else {
				IModel model = (IModel) input;
				auditModel = model.getModelSet().getModel(URIs.createURI("enilink:audit:" + model.getURI()), false);
			}
			setStale(true);
			return true;
		}
		return false;
	}

	@Override
	public void refresh() {
		IAdapterFactory newAdapterFactory = getAdapterFactory();
		if (adapterFactory == null || !adapterFactory.equals(newAdapterFactory)) {
			adapterFactory = newAdapterFactory;
			viewer.setLabelProvider(new AdapterFactoryLabelProvider(adapterFactory) {
				@Override
				public Image getColumnImage(Object object, int columnIndex) {
					return null;
				}

				@Override
				public String getColumnText(Object object, int columnIndex) {
					return Subject.doAs(SecurityUtil.SYSTEM_USER_SUBJECT, (PrivilegedAction<String>) () -> {
						if (object instanceof ChangeDescription) {
							ChangeDescription change = ((IEntity) object).as(ChangeDescription.class);
							switch (columnIndex) {
								case 0:
									return DateFormat.getDateTimeInstance().format(change.getDate()
											.toGregorianCalendar().getTime());
								default:
									return super.getText(change.getAgent());
							}
						} else if (object instanceof ChangeElement && columnIndex == 1) {
							ChangeElement changeElement = (ChangeElement) object;
							Statement stmt = changeElement.stmt;
							return (changeElement.added ? "+ " : "- ") + "[" +
									super.getText(stmt.getRdfSubject()) + " " +
									super.getText(stmt.getRdfPredicate()) + " " +
									super.getText(stmt.getRdfObject()) + "]";
						}
						return null;
					});
				}
			});
		}
		viewer.setInput(auditModel);
		super.refresh();
	}
}
