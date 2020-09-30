package net.enilink.platform.workbench;

import net.enilink.komma.model.IModelSet;
import net.enilink.platform.core.UseService;
import net.enilink.platform.core.security.LoginUtil;
import net.enilink.platform.workbench.auth.LoginDialog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.equinox.security.auth.ILoginContext;
import org.eclipse.equinox.security.auth.LoginContextFactory;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.rap.rwt.RWT;
import org.eclipse.rap.rwt.internal.service.ContextProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.application.IWorkbenchConfigurer;
import org.eclipse.ui.application.IWorkbenchWindowConfigurer;
import org.eclipse.ui.application.WorkbenchAdvisor;
import org.eclipse.ui.application.WorkbenchWindowAdvisor;
import org.eclipse.ui.plugin.AbstractUIPlugin;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.LoginException;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URL;
import java.security.PrivilegedAction;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * This class controls all aspects of the application's execution and is
 * contributed through the plugin.xml.
 */
public class Application implements IApplication {
	private static final String SUBJECT_KEY = "javax.security.auth.subject";

	private static final Map<String, String> jaasConfigurations = new LinkedHashMap<>();

	static {
		LoginUtil.getLoginMethods().forEach(m -> {
			jaasConfigurations.put(m.getFirst(), m.getSecond());
		});
	}

	static class DelegatingCallbackHandler implements CallbackHandler {
		CallbackHandler delegate;

		public void handle(Callback[] callbacks) throws UnsupportedCallbackException, IOException {
			delegate.handle(callbacks);
		}
	}

	/**
	 * Captures a login context instance for storing it in a user session.
	 */
	static class State {
		String method;
		ILoginContext context;
		DelegatingCallbackHandler handler = new DelegatingCallbackHandler();
	}

	public Object start(IApplicationContext context) throws Exception {
		// UICallBack.activate(getClass().getName());
		final Display display = PlatformUI.createDisplay();

		ContextProvider.getApplicationContext().getPhaseListenerManager().addPhaseListener(new UnitOfWorkPhaseListener());

		new UseService<IModelSet, Void>(IModelSet.class) {
			@Override
			protected Void withService(IModelSet modelSet) {
				modelSet.getUnitOfWork().begin();
				return null;
			}
		};

		HttpSession session = RWT.getRequest().getSession(true);
		Subject subject = (Subject) session.getAttribute(SUBJECT_KEY);
		ILoginContext sCtx = null;
		boolean loginRequired = LoginUtil.REQUIRE_LOGIN || Boolean.TRUE.equals(session.getAttribute("login.requested"));
		if (loginRequired && subject == null) {
			String loginMethod = (String) session.getAttribute("login.method");
			if (loginMethod == null) {
				loginMethod = jaasConfigurations.keySet().iterator().next();
			}

			display.setData("login.methods", jaasConfigurations.keySet());
			display.setData("login.method", loginMethod);

			ImageDescriptor titleImageDesc = AbstractUIPlugin.imageDescriptorFromPlugin("net.enilink.platform.workbench", "/img/enilink/login_title.png");

			display.setData("login.title", "Login - eniLINK");
			display.setData("login.imageDescriptor", titleImageDesc);

			subject = null;
			boolean retry = true;
			while (null == subject && retry) {
				loginMethod = (String) display.getData("login.method");
				session.setAttribute("login.method", loginMethod);

				// use login context from session or create a new one
				State loginState = (State) session.getAttribute("login.state");
				if (loginState == null || !loginMethod.equals(loginState.method)) {
					loginState = new State();
					loginState.method = loginMethod;
					URL cfgUrl = LoginUtil.getJaasConfigUrl();
					loginState.context = LoginContextFactory.createContext(jaasConfigurations.get(loginState.method),
							cfgUrl, loginState.handler);
					session.setAttribute("login.state", loginState);
				}
				loginState.handler.delegate = new LoginDialog();
				sCtx = loginState.context;

				try {
					sCtx.login();
					subject = sCtx.getSubject();
				} catch (LoginException e) {
					ThreadDeath threadDeath = (ThreadDeath) display.getData("login.exception.threaddeath");
					if (threadDeath != null) {
						display.setData("login.exception.threaddeath", null);
						throw threadDeath;
					}
					if (!Boolean.TRUE.equals(display.getData("login.canceled"))) {
						// required for Equinox security to retrieve the
						// real cause for this exception
						if (e.getCause() instanceof LoginException) {
							e = (LoginException) e.getCause();
						}

						IStatus status = new Status(IStatus.ERROR, EnilinkWorkbenchPlugin.INSTANCE.getSymbolicName(), e.getMessage());
						retry = ErrorDialog.openError(null, "Error", "Login failed", status) == Dialog.OK;
					} else {
						retry = !loginMethod.equals(display.getData("login.method"));
					}
				}
			}
			session.removeAttribute("login.requested");
		}

		Integer result = 1;
		if (LoginUtil.REQUIRE_LOGIN && subject == null) {
			MessageDialog.openConfirm(null, "Access denied", "Access to the application has been denied.");
		} else if (subject != null) {
			try {
				if (sCtx != null) {
					session.setAttribute(SUBJECT_KEY, subject);
				}
				session.removeAttribute("login.state");
				result = Subject.doAs(subject, new PrivilegedAction<Integer>() {
					public Integer run() {
						return runWorkbench(display);
					}
				});
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				Object logout = display.getData("logout");
				if (Boolean.TRUE.equals(logout)) {
					session.removeAttribute(SUBJECT_KEY);
					if (null != sCtx) {
						sCtx.logout();
					}
				}
				display.dispose();
			}
		} else {
			result = runWorkbench(display);
		}
		return result;
	}

	protected int runWorkbench(Display display) {
		WorkbenchAdvisor advisor = new WorkbenchAdvisor() {
			@Override
			public void initialize(IWorkbenchConfigurer configurer) {
				super.initialize(configurer);
			}

			@Override
			public WorkbenchWindowAdvisor createWorkbenchWindowAdvisor(IWorkbenchWindowConfigurer configurer) {
				configurer.setShowFastViewBars(true);
				configurer.setShowPerspectiveBar(true);
				configurer.setShowCoolBar(true);
				configurer.setShowMenuBar(false);
				return new WorkbenchWindowAdvisor(configurer) {
					@Override
					public void preWindowOpen() {
						getWindowConfigurer().setShellStyle(SWT.NO_TRIM);
					}

					@Override
					public void postWindowCreate() {
						Shell shell = getWindowConfigurer().getWindow().getShell();
						shell.setMaximized(true);
					}
				};
			}

			public String getInitialWindowPerspectiveId() {
				return "net.enilink.platform.workbench.basicPerspective";
			}
		};
		return PlatformUI.createAndRunWorkbench(display, advisor);
	}

	public void stop() {
		final IWorkbench workbench = PlatformUI.getWorkbench();
		if (workbench == null) {
			return;
		}
		final Display display = workbench.getDisplay();
		if (display == null) {
			return;
		}
		display.syncExec(new Runnable() {
			public void run() {
				if (!display.isDisposed()) {
					workbench.close();
				}
			}
		});
	}
}
