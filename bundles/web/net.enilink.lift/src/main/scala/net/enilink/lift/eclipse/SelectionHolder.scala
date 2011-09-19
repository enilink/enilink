package net.enilink.lift.eclipse
import org.eclipse.jface.viewers.ISelection
import org.eclipse.ui.part.WorkbenchPart
import org.eclipse.ui.ISelectionListener
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.IWorkbenchPart
import org.eclipse.jface.viewers.IStructuredSelection
import net.enilink.komma.core.IValue
import org.eclipse.core.runtime.Platform
import net.enilink.komma.model.IObject
import net.enilink.komma.model.IModel

object SelectionHolder {
  protected var currentSelection: IObject = null

  def init = {
    PlatformUI.getWorkbench().getDisplay().asyncExec(new Runnable() {
      def run() {
        PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().addSelectionListener(new ISelectionListener() {
          def selectionChanged(part: IWorkbenchPart, selection: ISelection) {
            if (selection.isInstanceOf[IStructuredSelection]) {
              var selected = selection.asInstanceOf[IStructuredSelection]
                .getFirstElement();

              // allow arbitrary selections to be adapted to IValue objects
              if (selected != null && !(selected.isInstanceOf[IValue])) {
                val adapter = Platform.getAdapterManager().getAdapter(
                  selected, classOf[IValue]);
                if (adapter != null) {
                  selected = adapter;
                }
              }

              if (selected.isInstanceOf[IObject]) {
                currentSelection = selected.asInstanceOf[IObject]
              }
            }
          }
        })
      }
    })
  }
  
  def getSelection() = currentSelection
}