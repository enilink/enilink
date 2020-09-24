package net.enilink.platform.workbench;

import net.enilink.komma.model.IModelSet;
import net.enilink.platform.core.UseService;
import net.enilink.platform.workbench.auth.LoginDialog;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
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
import org.eclipse.swt.widgets.Display;
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
import java.io.File;
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

	private static final String KERBEROS_CONFIG_FILE = "/resources/krb5.iwu.conf";

	private static final String JAAS_CONFIG_FILE = "/resources/jaas.conf";

	private static final Map<String, String> jaasConfigurations = new LinkedHashMap<String, String>();

	static {
		jaasConfigurations.put("IWU Share", "CMIS");
		jaasConfigurations.put("OpenID", "OpenID");
		// jaasConfigurations.put("Kerberos", "Kerberos");
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

	private static final boolean REQUIRE_LOGIN = false || "true".equalsIgnoreCase(System.getProperty("enilink.loginrequired"));

	static {
		try {
			System.setProperty("java.security.krb5.conf", new File(FileLocator.resolve(Platform.getBundle("net.enilink.platform.core").getResource(KERBEROS_CONFIG_FILE)).toURI()).getAbsolutePath());
		} catch (Exception e) {
			// ignore
		}
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
		boolean loginRequired = REQUIRE_LOGIN || Boolean.TRUE.equals(session.getAttribute("login.requested"));
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
					URL cfgUrl = Platform.getBundle("net.enilink.platform.core").getResource(JAAS_CONFIG_FILE);
					loginState.context = LoginContextFactory.createContext(jaasConfigurations.get(loginState.method), cfgUrl);
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
		if (REQUIRE_LOGIN && subject == null) {
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
				configurer.setShowMenuBar(true);
				return super.createWorkbenchWindowAdvisor(configurer);
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
