package net.enilink.platform.workbench.auth;

import net.enilink.platform.security.auth.EnilinkPrincipal;
import org.eclipse.jface.action.ContributionItem;
import org.eclipse.rap.rwt.RWT;
import org.eclipse.rap.rwt.client.service.JavaScriptExecutor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.PlatformUI;

import javax.security.auth.Subject;
import java.security.AccessController;
import java.security.Principal;
import java.security.acl.Group;
import java.util.Set;

public class UserMenu extends ContributionItem {
	public UserMenu() {
	}

	public UserMenu(String id) {
		super(id);
	}

	@Override
	public void fill(final ToolBar toolBar, int index) {
		boolean loggedIn = false;

		Subject subject = Subject.getSubject(AccessController.getContext());
		loggedIn = subject != null;
		String username = null;

		if (loggedIn) {
			Set<EnilinkPrincipal> users = subject.getPrincipals(EnilinkPrincipal.class);
			if (!users.isEmpty()) {
				username = users.iterator().next().getName();
			}
			if (username == null) {
				for (Principal principal : subject.getPrincipals()) {
					if (!(principal instanceof Group)) {
						username = principal.getName();
					}
					if (username != null) {
						break;
					}
				}
			}
			if (username == null || username.trim().length() == 0) {
				username = "You";
			}

			final Menu menu = new Menu(toolBar.getShell(), SWT.POP_UP);
			{
				MenuItem item = new MenuItem(menu, SWT.PUSH);
				item.setText(username);
				item.setEnabled(false);

				item = new MenuItem(menu, SWT.PUSH);
				item.setText("Logout");
				item.addSelectionListener(new SelectionAdapter() {
					@Override
					public void widgetSelected(SelectionEvent e) {
						PlatformUI.getWorkbench().getDisplay().setData("logout", true);
						PlatformUI.getWorkbench().close();
					}
				});
			}

			final ToolItem item = new ToolItem(toolBar, SWT.DROP_DOWN);
			item.setData(RWT.CUSTOM_VARIANT, "userMenu");
			item.setText(username);
			item.addSelectionListener(new SelectionAdapter() {
				@Override
				public void widgetSelected(SelectionEvent e) {
					Rectangle rect = item.getBounds();
					Point pt = new Point(rect.x, rect.y + rect.height);
					pt = toolBar.toDisplay(pt);
					menu.setLocation(pt.x, pt.y);
					menu.setVisible(true);
				}
			});
			item.addDisposeListener(new DisposeListener() {
				@Override
				public void widgetDisposed(DisposeEvent event) {
					menu.dispose();
				}
			});
		} else {
			final ToolItem item = new ToolItem(toolBar, SWT.PUSH);
			item.setData(RWT.CUSTOM_VARIANT, "userMenu");
			item.setText("Login");
			item.addSelectionListener(new SelectionAdapter() {
				@Override
				public void widgetSelected(SelectionEvent e) {
					RWT.getRequest().getSession(true).setAttribute("login.requested", true);
					RWT.getClient().getService(JavaScriptExecutor.class).execute("parent.window.location.reload();");
				}
			});
		}
	}
}
