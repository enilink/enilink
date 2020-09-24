package net.enilink.platform.workbench;

import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorMatchingStrategy;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.PartInitException;

public class ModelEditorMatchingStrategy implements IEditorMatchingStrategy {
	@Override
	public boolean matches(IEditorReference editorRef, IEditorInput input) {
		try {
			if (input instanceof ModelEditorInput && editorRef.getEditorInput() instanceof ModelEditorInput) {
				return ((ModelEditorInput) input).getModel().equals(((ModelEditorInput) editorRef.getEditorInput()).getModel());
			}
		} catch (PartInitException e) {
			EnilinkWorkbenchPlugin.getPlugin().log(e);
		}
		return false;
	}

}
