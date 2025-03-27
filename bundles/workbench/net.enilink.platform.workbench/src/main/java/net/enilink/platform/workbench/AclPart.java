package net.enilink.platform.workbench;

import net.enilink.komma.common.adapter.IAdapterFactory;
import net.enilink.komma.common.command.CommandResult;
import net.enilink.komma.common.command.ICommand;
import net.enilink.komma.common.command.SimpleCommand;
import net.enilink.komma.common.ui.assist.ContentProposals;
import net.enilink.komma.core.IEntity;
import net.enilink.komma.core.IQuery;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.IStatement;
import net.enilink.komma.core.Statement;
import net.enilink.komma.core.URI;
import net.enilink.komma.edit.command.DeleteCommand;
import net.enilink.komma.edit.domain.IEditingDomain;
import net.enilink.komma.edit.properties.EditingHelper;
import net.enilink.komma.edit.properties.IEditingSupport;
import net.enilink.komma.edit.properties.ResourceEditingSupport;
import net.enilink.komma.edit.provider.IItemLabelProvider;
import net.enilink.komma.edit.ui.assist.JFaceProposalProvider;
import net.enilink.komma.edit.ui.celleditor.PropertyCellEditingSupport;
import net.enilink.komma.edit.ui.properties.IEditUIPropertiesImages;
import net.enilink.komma.edit.ui.properties.KommaEditUIPropertiesPlugin;
import net.enilink.komma.edit.ui.provider.AdapterFactoryContentProvider;
import net.enilink.komma.edit.ui.provider.AdapterFactoryLabelProvider;
import net.enilink.komma.edit.ui.provider.ExtendedImageRegistry;
import net.enilink.komma.edit.ui.util.EditUIUtil;
import net.enilink.komma.edit.ui.views.AbstractEditingDomainPart;
import net.enilink.komma.em.concepts.IProperty;
import net.enilink.komma.em.concepts.IResource;
import net.enilink.komma.model.IModel;
import net.enilink.komma.model.IObject;
import net.enilink.platform.core.security.ISecureEntity;
import net.enilink.platform.core.security.SecurityUtil;
import net.enilink.vocab.acl.Authorization;
import net.enilink.vocab.acl.WEBACL;
import net.enilink.vocab.rdfs.Class;
import java.util.Set;

import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.fieldassist.ContentProposalAdapter;
import org.eclipse.jface.fieldassist.IContentProposal;
import org.eclipse.jface.fieldassist.IContentProposalListener;
import org.eclipse.jface.fieldassist.IContentProposalProvider;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.CheckboxCellEditor;
import org.eclipse.jface.viewers.EditingSupport;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.forms.widgets.Section;

public class AclPart extends AbstractEditingDomainPart {
	private IAdapterFactory adapterFactory;
	private TableViewer viewer;
	private Text ownerText;
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
		ownerText = getWidgetFactory().createText(section, "", SWT.SINGLE);
		ownerText.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true,
				false));

		EditingHelper helper = new EditingHelper(EditingHelper.Type.VALUE) {
			@Override
			protected IEditingDomain getEditingDomain() {
				return AclPart.this.getEditingDomain();
			}
		};
		ContentProposalAdapter proposalAdapter = ContentProposals.enableContentProposal(ownerText, (IContentProposalProvider) (contents, position) -> {
			if (target != null) {
				IStatement stmt = new Statement(target,
						target.getEntityManager().find(WEBACL.PROPERTY_OWNER), null);
				return JFaceProposalProvider.wrap(helper.getProposalSupport(stmt).getProposalProvider())
						.getProposals(contents, position);
			}
			return new IContentProposal[0];
		}, null);
		proposalAdapter.addContentProposalListener((IContentProposalListener) proposal -> {
		});

		section.setClient(ownerText);
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
				return new Statement((IEntity) element,
						((IEntity) element).getEntityManager().find(WEBACL.PROPERTY_AGENT, IProperty.class),
						((Authorization) element).getAclAgent());
			}

			@Override
			protected IEditingDomain getEditingDomain() {
				return AclPart.this.getEditingDomain();
			}

			@Override
			protected IEditingSupport getEditingSupport(Object element) {
				return super.getEditingSupport(element);
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
				return new Statement((IEntity) element,
						((IEntity) element).getEntityManager().find(WEBACL.PROPERTY_AGENTCLASS, IProperty.class),
						((Authorization) element).getAclAgentClass());
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
		createActions();
		viewer.addSelectionChangedListener(event -> deleteAction.setEnabled(!event.getSelection().isEmpty()));
	}

	private void executeCommand(ICommand command) {
		IStatus status;
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
					protected CommandResult doExecuteWithResult(IProgressMonitor progressMonitor, IAdaptable info) {
						Authorization auth = target.getEntityManager().create(Authorization.class);
						auth.setAclAccessTo((IResource) target);
						auth.getAclMode().add(target.getEntityManager().find(WEBACL.MODE_READ, Class.class));
						return CommandResult.newOKCommandResult(auth);
					}
				});
				viewer.refresh();
			}
		};
		addAction.setImageDescriptor(ExtendedImageRegistry.getInstance().getImageDescriptor(KommaEditUIPropertiesPlugin.INSTANCE.getImage(IEditUIPropertiesImages.ADD)));
		toolBarManager.add(addAction);

		deleteAction = new Action("Remove") {
			public void run() {
				ICommand deleteCommand = DeleteCommand.create(getEditingDomain(), ((IStructuredSelection) viewer.getSelection()).getFirstElement());
				executeCommand(deleteCommand);
				viewer.refresh();
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
			viewer.setContentProvider(new AdapterFactoryContentProvider(adapterFactory) {
				@Override
				public Object[] getElements(Object inputElement) {
					if (inputElement != null) {
						IQuery<?> query = ((IEntity) inputElement).getEntityManager().createQuery(
								"select ?authorization { ?authorization ?accessTo ?target }");
						query.setParameter("target", inputElement);
						query.setParameter("accessTo", WEBACL.PROPERTY_ACCESSTO);
						return query.evaluate().toList().toArray();
					}
					return new Object[0];
				}
			});
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
					return null;
				}

				@Override
				public String getColumnText(Object object, int columnIndex) {
					if (columnIndex >= MODES_OFFSET) {
						return null;
					}
					Object target = getTarget(object, columnIndex);
					return target == null ? null : super.getText(target);
				}
			});
		}
		if (target == null) {
			targetLabel.setImage(null);
			targetLabel.setText("");
			ownerText.setText("");
			viewer.setInput(null);
		} else {
			targetLabel.setImage(((ILabelProvider) viewer.getLabelProvider()).getImage(target));
			targetLabel.setText(((ILabelProvider) viewer.getLabelProvider()).getText(target));
			targetLabel.getParent().layout(true);
			viewer.setInput(target);

			ISecureEntity secureTarget = target.as(ISecureEntity.class);

			// the user is able to change permissions for the target, if he is
			// the owner or has access type 'Control' for the target
			boolean canControl = secureTarget != null &&
					(SecurityUtil.getUser().equals(secureTarget.getAclOwner()) ||
							secureTarget.hasAclMode(SecurityUtil.getUser(), WEBACL.MODE_CONTROL));
			if (!canControl && target instanceof IObject) {
				secureTarget = ((IEntity) ((IObject) target).getModel()).as(ISecureEntity.class);
				canControl = secureTarget != null &&
						(SecurityUtil.getUser().equals(secureTarget.getAclOwner()) ||
								secureTarget.hasAclMode(SecurityUtil.getUser(), WEBACL.MODE_CONTROL));
			}

			IReference owner = secureTarget.getAclOwner();
			IItemLabelProvider ownerLabelProvider = (IItemLabelProvider) adapterFactory
					.adapt(owner, IItemLabelProvider.class);
			ownerText.setText(ownerLabelProvider != null ? ownerLabelProvider.getText(owner) : null);
			ownerText.setEnabled(canControl);
			viewer.getTable().setEnabled(canControl);
		}
		super.refresh();
	}
}
