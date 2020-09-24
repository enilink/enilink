package net.enilink.platform.workbench.snippets;

import net.enilink.commons.ui.editor.IEditorPart;
import net.enilink.komma.owl.editor.classes.ClassesPart;

public class ClassesSnippet extends AbstractSnippet {
	@Override
	IEditorPart createEditorPart() {
		return new ClassesPart();
	}
}
