package net.enilink.platform.workbench.model;

import net.enilink.komma.core.KommaModule;

public class WorkbenchModule extends KommaModule {
	{
		addConcept(ChangeDescription.class);
		addConcept(ChangeStatement.class);
	}
}
