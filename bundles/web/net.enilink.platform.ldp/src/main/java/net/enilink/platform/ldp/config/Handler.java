package net.enilink.platform.ldp.config;

import net.enilink.komma.core.URI;

public interface Handler {
	boolean isDeletable();

	boolean isModifyable();

	URI getAssignedTo();
}
