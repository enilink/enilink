package net.enilink.platform.workbench;

import net.enilink.komma.common.adapter.IAdapterFactory;
import net.enilink.komma.core.IEntity;
import net.enilink.komma.core.IQuery;
import net.enilink.komma.core.URIs;
import net.enilink.komma.edit.ui.provider.AdapterFactoryLabelProvider;
import net.enilink.komma.edit.ui.views.AbstractEditingDomainPart;
import net.enilink.komma.em.util.ISparqlConstants;
import net.enilink.komma.model.IModel;
import net.enilink.platform.core.security.SecurityUtil;
import net.enilink.platform.workbench.model.ChangeDescription;
import net.enilink.vocab.komma.KOMMA;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Table;

import javax.security.auth.Subject;
import java.security.PrivilegedAction;
import java.text.DateFormat;

public class HistoryPart extends AbstractEditingDomainPart {
	private IAdapterFactory adapterFactory;
	private TableViewer viewer;
	private IModel auditModel;

	@Override
	public void createContents(Composite parent) {
		parent.setLayout(new FillLayout());

		Table table = getWidgetFactory().createTable(parent, SWT.V_SCROLL | SWT.H_SCROLL);

		viewer = new TableViewer(table);
		viewer.getTable().setHeaderVisible(true);
		viewer.setUseHashlookup(true);

		TableViewerColumn dateColumn = new TableViewerColumn(viewer, SWT.LEFT);
		dateColumn.getColumn().setText("Date");
		dateColumn.getColumn().setWidth(300);

		TableViewerColumn agentColumn = new TableViewerColumn(viewer, SWT.LEFT);
		agentColumn.getColumn().setText("Agent");
		agentColumn.getColumn().setWidth(300);

		viewer.setContentProvider(new IStructuredContentProvider() {
			@Override
			public Object[] getElements(Object inputElement) {
				return Subject.doAs(SecurityUtil.SYSTEM_USER_SUBJECT, (PrivilegedAction<Object[]>) () -> {
					if (inputElement instanceof IModel) {
						IQuery<?> query = ((IModel) inputElement).getManager().createQuery(
								ISparqlConstants.PREFIX +
										"prefix dcterms: <http://purl.org/dc/terms/> " +
										"select ?change { ?change a ?changeDescription ; dcterms:date ?date } order by desc(?date) limit 10");
						query.setParameter("changeDescription", KOMMA.NAMESPACE_URI.appendLocalPart("ChangeDescription"));
						return query.evaluate().toList().toArray();
					}
					return new Object[0];
				});
			}

			@Override
			public void dispose() {
			}

			@Override
			public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
			}
		});
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
						ChangeDescription change = ((IEntity) object).as(ChangeDescription.class);
						switch (columnIndex) {
							case 0:
								return DateFormat.getDateTimeInstance().format(change.getDate()
										.toGregorianCalendar().getTime());
							default:
								return super.getText(change.getAgent());
						}
					});
				}
			});
		}
		viewer.setInput(auditModel);
		super.refresh();
	}
}
