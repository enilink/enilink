package net.enilink.platform.workbench;

import net.enilink.komma.edit.ui.views.AbstractEditingDomainView;

public class AclView extends AbstractEditingDomainView {
	public static final String ID = "net.enilink.platform.workbench.aclView";

	public AclView() {
		setEditPart(new AclPart());
	}

	@Override
	protected void installSelectionProvider() {
		// do nothing
	}
}