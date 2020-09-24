package net.enilink.platform.workbench;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Provides;
import net.enilink.komma.common.adapter.IAdapterFactory;
import net.enilink.komma.common.command.CommandResult;
import net.enilink.komma.common.command.ICommand;
import net.enilink.komma.common.command.SimpleCommand;
import net.enilink.komma.core.*;
import net.enilink.komma.edit.command.DeleteCommand;
import net.enilink.komma.edit.domain.IEditingDomain;
import net.enilink.komma.edit.properties.IEditingSupport;
import net.enilink.komma.edit.properties.ResourceEditingSupport;
import net.enilink.komma.edit.ui.celleditor.PropertyCellEditingSupport;
import net.enilink.komma.edit.ui.properties.IEditUIPropertiesImages;
import net.enilink.komma.edit.ui.properties.KommaEditUIPropertiesPlugin;
import net.enilink.komma.edit.ui.provider.AdapterFactoryLabelProvider;
import net.enilink.komma.edit.ui.provider.ExtendedImageRegistry;
import net.enilink.komma.edit.ui.provider.reflective.StatementPatternContentProvider;
import net.enilink.komma.edit.ui.util.EditUIUtil;
import net.enilink.komma.edit.ui.viewers.PropertyViewer;
import net.enilink.komma.edit.ui.views.AbstractEditingDomainPart;
import net.enilink.komma.em.concepts.IProperty;
import net.enilink.komma.em.concepts.IResource;
import net.enilink.komma.model.IModel;
import net.enilink.platform.core.security.ISecureEntity;
import net.enilink.platform.core.security.SecurityUtil;
import net.enilink.vocab.acl.Authorization;
import net.enilink.vocab.acl.WEBACL;
import net.enilink.vocab.rdfs.Class;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Table;
import org.eclipse.ui.forms.widgets.Section;

import java.util.Set;

public class AclPart extends AbstractEditingDomainPart {
	private IAdapterFactory adapterFactory;
	private TableViewer viewer;
	private PropertyViewer ownerViewer;
	private Action addAction, deleteAction;

	private CLabel targetLabel;

	private IEntity target;

	private static final URI[] accessModes = {WEBACL.MODE_READ, WEBACL.MODE_WRITE, WEBACL.MODE_CONTROL};

	@Override
	public void createContents(Composite parent) {
		parent.setLayout(new GridLayout(2, false));

		Section section = getWidgetFactory().createSection(parent, Section.TITLE_BAR | Section.EXPANDED);
		section.setText("Item");
		targetLabel = getWidgetFactory().createCLabel(section, "");
		targetLabel.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));
		section.setClient(targetLabel);
		section.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));

		section = getWidgetFactory().createSection(parent, Section.TITLE_BAR | Section.EXPANDED);
		section.setText("Owner");
		ownerViewer = new PropertyViewer(section, SWT.SINGLE, getWidgetFactory());
		Guice.createInjector(new AbstractModule() {
			@Override
			protected void configure() {
			}

			@Provides
			IAdapterFactory provideAdapterFactory() {
				return getAdapterFactory();
			}

			@Provides
			IEditingDomain provideEditingDomain() {
				return getEditingDomain();
			}
		}).injectMembers(ownerViewer);
		section.setClient(ownerViewer.getControl());
		section.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));

		section = getWidgetFactory().createSection(parent, Section.TITLE_BAR | Section.EXPANDED);
		section.setText("Permissions");
		Table table = getWidgetFactory().createTable(section, SWT.V_SCROLL | SWT.H_SCROLL | SWT.VIRTUAL);
		section.setClient(table);
		GridDataFactory.fillDefaults().grab(true, true).span(2, 1).applyTo(section);

		viewer = new TableViewer(table);
		viewer.getTable().setHeaderVisible(true);
		viewer.setUseHashlookup(true);

		TableViewerColumn agentColumn = new TableViewerColumn(viewer, SWT.LEFT);
		agentColumn.getColumn().setText("Agent");
		agentColumn.setEditingSupport(new PropertyCellEditingSupport(viewer) {
			@Override
			protected IStatement getStatement(Object element) {
				return new Statement((IEntity) element, ((IEntity) element).getEntityManager().find(WEBACL.PROPERTY_AGENT, IProperty.class), ((Authorization) element).getAclAgent());
			}

			@Override
			protected IEditingDomain getEditingDomain() {
				return AclPart.this.getEditingDomain();
			}

			@Override
			protected IEditingSupport getEditingSupport(Object element) {
				return new ResourceEditingSupport(getAdapterFactory());
			}

			@Override
			protected void setEditStatus(Object element, IStatus status, Object value) {
				super.setEditStatus(element, status, value);
				if (target != null && status.isOK()) {
					// ensure that cached ACL data is refreshed
					target.refresh();
				}
			}
		});
		agentColumn.getColumn().setWidth(300);

		TableViewerColumn agentClassColumn = new TableViewerColumn(viewer, SWT.LEFT);
		agentClassColumn.getColumn().setText("AgentClass");
		agentClassColumn.setEditingSupport(new PropertyCellEditingSupport(viewer) {
			@Override
			protected IStatement getStatement(Object element) {
				return new Statement((IEntity) element, ((IEntity) element).getEntityManager().find(WEBACL.PROPERTY_AGENTCLASS, IProperty.class), ((Authorization) element).getAclAgentClass());
			}

			@Override
			protected IEditingDomain getEditingDomain() {
				return AclPart.this.getEditingDomain();
			}

			@Override
			protected IEditingSupport getEditingSupport(Object element) {
				return new ResourceEditingSupport(getAdapterFactory());
			}

			@Override
			protected void setEditStatus(Object element, IStatus status, Object value) {
				super.setEditStatus(element, status, value);
				if (target != null && status.isOK()) {
					// ensure that cached ACL data is refreshed
					target.refresh();
				}
			}
		});
		agentClassColumn.getColumn().setWidth(300);

		final CheckboxCellEditor editor = new CheckboxCellEditor(viewer.getTable());
		for (final URI mode : accessModes) {
			TableViewerColumn modeColumn = new TableViewerColumn(viewer, SWT.LEFT);
			modeColumn.getColumn().setText(mode.localPart());
			modeColumn.setEditingSupport(new EditingSupport(viewer) {
				@Override
				protected void setValue(Object element, Object value) {
					Class modeClass = ((IEntity) element).getEntityManager().find(mode, Class.class);
					Set<Class> aclModes = ((Authorization) element).getAclMode();
					if (Boolean.TRUE.equals(value)) {
						aclModes.add(modeClass);
					} else {
						aclModes.remove(modeClass);
					}
				}

				@Override
				protected Object getValue(Object element) {
					return ((Authorization) element).getAclMode().contains(mode);
				}

				@Override
				protected CellEditor getCellEditor(Object element) {
					return editor;
				}

				@Override
				protected boolean canEdit(Object element) {
					return true;
				}
			});
			modeColumn.getColumn().pack();
		}
		viewer.setContentProvider(new StatementPatternContentProvider());
		createActions();
		viewer.addSelectionChangedListener(new ISelectionChangedListener() {
			public void selectionChanged(SelectionChangedEvent event) {
				deleteAction.setEnabled(!event.getSelection().isEmpty());
			}
		});
	}

	private void executeCommand(ICommand command) {
		IStatus status = Status.CANCEL_STATUS;
		try {
			status = getEditingDomain().getCommandStack().execute(command, null, null);
		} catch (ExecutionException exception) {
			status = EditUIUtil.createErrorStatus(exception);
		}
		if (!status.isOK()) {
			MessageDialog.openError(getShell(), "Error", status.getMessage());
		}
	}

	protected void createActions() {
		IToolBarManager toolBarManager = getForm().getAdapter(IToolBarManager.class);
		if (toolBarManager == null) {
			return;
		}
		addAction = new Action("Add") {
			public void run() {
				executeCommand(new SimpleCommand() {
					@Override
					protected CommandResult doExecuteWithResult(IProgressMonitor progressMonitor, IAdaptable info) throws ExecutionException {
						Authorization auth = target.getEntityManager().create(Authorization.class);
						auth.setAclAccessTo((IResource) target);
						auth.getAclMode().add(target.getEntityManager().find(WEBACL.MODE_READ, Class.class));
						return CommandResult.newOKCommandResult(auth);
					}
				});
			}
		};
		addAction.setImageDescriptor(ExtendedImageRegistry.getInstance().getImageDescriptor(KommaEditUIPropertiesPlugin.INSTANCE.getImage(IEditUIPropertiesImages.ADD)));
		toolBarManager.add(addAction);

		deleteAction = new Action("Remove") {
			public void run() {
				ICommand deleteCommand = DeleteCommand.create(getEditingDomain(), ((IStructuredSelection) viewer.getSelection()).getFirstElement());
				executeCommand(deleteCommand);
			}
		};
		deleteAction.setImageDescriptor(ExtendedImageRegistry.getInstance().getImageDescriptor(KommaEditUIPropertiesPlugin.INSTANCE.getImage(IEditUIPropertiesImages.REMOVE)));
		deleteAction.setEnabled(false);
		toolBarManager.add(deleteAction);
	}

	@Override
	public void setInput(Object input) {
		if (input instanceof IModel) {
			setEditorInput(input);
		}
	}

	@Override
	public boolean setEditorInput(Object input) {
		if (input instanceof IEntity || input == null) {
			target = (IEntity) input;
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
			ownerViewer.setLabelProvider(new AdapterFactoryLabelProvider(adapterFactory));
			viewer.setLabelProvider(new AdapterFactoryLabelProvider(adapterFactory) {
				static final int MODES_OFFSET = 2;

				Object getTarget(Object object, int columnIndex) {
					Authorization authorization = ((IEntity) object).as(Authorization.class);
					switch (columnIndex) {
						case 0:
							return authorization.getAclAgent();
						default:
							return authorization.getAclAgentClass();
					}
				}

				@Override
				public Image getColumnImage(Object object, int columnIndex) {
					if (columnIndex >= MODES_OFFSET) {
						if (((IEntity) object).as(Authorization.class).getAclMode().contains(accessModes[columnIndex - MODES_OFFSET])) {
							return ExtendedImageRegistry.getInstance().getImage(KommaEditUIPropertiesPlugin.INSTANCE.getImage(IEditUIPropertiesImages.CHECKED));
						} else {
							return null;
						}
					}
					return super.getImage(getTarget(object, columnIndex));
				}

				@Override
				public String getColumnText(Object object, int columnIndex) {
					if (columnIndex >= MODES_OFFSET) {
						return null;
					}
					return super.getText(getTarget(object, columnIndex));
				}
			});
		}
		if (target == null) {
			targetLabel.setImage(null);
			targetLabel.setText("");
			ownerViewer.setEditingSupport(null);
			ownerViewer.setInput(null);
			viewer.setInput(null);
		} else {
			targetLabel.setImage(((ILabelProvider) viewer.getLabelProvider()).getImage(target));
			targetLabel.setText(((ILabelProvider) viewer.getLabelProvider()).getText(target));
			targetLabel.getParent().layout(true);
			ownerViewer.setEditingSupport(new ResourceEditingSupport(getAdapterFactory()));
			ownerViewer.setInput(new StatementPattern(target, WEBACL.PROPERTY_OWNER, null));
			viewer.setInput(new StatementPattern(null, WEBACL.PROPERTY_ACCESSTO, target));

			ISecureEntity secureTarget = target.as(ISecureEntity.class);
			// the user is able to change permissions for the target, if he is
			// the owner or has access type 'Control' for the target
			boolean canControl = secureTarget != null && (SecurityUtil.getUser().equals(secureTarget.getAclOwner()) || secureTarget.hasAclMode(SecurityUtil.getUser(), WEBACL.MODE_CONTROL));
			ownerViewer.getControl().setEnabled(canControl);
			viewer.getTable().setEnabled(canControl);
		}
		super.refresh();
	}
}
