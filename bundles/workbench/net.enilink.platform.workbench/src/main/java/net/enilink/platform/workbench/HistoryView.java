package net.enilink.platform.workbench;

import net.enilink.komma.edit.ui.views.AbstractEditingDomainView;

public class HistoryView extends AbstractEditingDomainView {
	public static final String ID = "net.enilink.platform.workbench.historyView";

	public HistoryView() {
		setEditPart(new HistoryPart());
	}

	@Override
	protected void installSelectionProvider() {
		// do nothing
	}
}