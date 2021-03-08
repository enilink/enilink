package net.enilink.platform.ldp.config;

import net.enilink.komma.core.URI;

public interface Handler {
		boolean isDeleteable();
		boolean isModifyable();
		URI getAssignedTo();
}
