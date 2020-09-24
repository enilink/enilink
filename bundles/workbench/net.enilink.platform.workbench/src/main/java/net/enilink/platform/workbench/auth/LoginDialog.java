/*******************************************************************************
 * Copyright (c) 2005, 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package net.enilink.platform.workbench.auth;

import net.enilink.platform.security.callbacks.RealmCallback;
import net.enilink.platform.security.callbacks.RedirectCallback;
import net.enilink.platform.security.callbacks.RegisterCallback;
import net.enilink.platform.security.callbacks.ResponseCallback;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.rap.rwt.RWT;
import org.eclipse.rap.rwt.client.service.JavaScriptExecutor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.*;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.forms.events.HyperlinkAdapter;
import org.eclipse.ui.forms.events.HyperlinkEvent;
import org.eclipse.ui.forms.widgets.Hyperlink;

import javax.security.auth.callback.*;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;


public class LoginDialog extends TitleAreaDialog implements CallbackHandler {
	private static final int COOKIE_MAX_AGE_SEC = 3600 * 24 * 90; // 3 months
	private static final String COOKIE_NAME = "loginData";
	private static final String METHODS_KEY = "login.methods";
	private static final String CURRENT_METHOD_KEY = "login.method";

	Callback[] callbackArray;
	boolean isCancelled = false;

	private Properties loginData;
	private boolean loginDataModified = false;

	boolean showButtons = true;

	private List<String> loginMethods;
	private String selectedMethod;

	public LoginDialog() {
		super(null);
	}

	protected void createButtonsForButtonBar(Composite parent) {
		if (showButtons) {
			super.createButtonsForButtonBar(parent);

			final Button okButton = getButton(IDialogConstants.OK_ID);
			okButton.setText("Login");
			final Button cancel = getButton(IDialogConstants.CANCEL_ID);
			cancel.addSelectionListener(new SelectionAdapter() {
				public void widgetSelected(final SelectionEvent event) {
					isCancelled = true;
				}
			});
		}
	}

	private void createCallbackHandlers(Composite composite) {
		showButtons = true;

		Callback[] callbacks = getCallbacks();
		for (int i = 0; i < callbacks.length; i++) {
			Callback callback = callbacks[i];
			if (callback instanceof TextOutputCallback) {
				createTextoutputHandler(composite, (TextOutputCallback) callback);
			} else if (callback instanceof TextInputCallback) {
				createTextInputHandler(composite, (TextInputCallback) callback);
			} else if (callback instanceof NameCallback) {
				createNameHandler(composite, (NameCallback) callback);
			} else if (callback instanceof PasswordCallback) {
				createPasswordHandler(composite, (PasswordCallback) callback);
			} else if (callback instanceof RedirectCallback) {
				createRedirectMessage(composite);
				showButtons = false;
			}
		}
	}

	protected Control createDialogArea(Composite parent) {
		Composite dialogArea = (Composite) super.createDialogArea(parent);

		if (!loginMethods.isEmpty()) {
			Composite loginMethods = createMethodSelector(dialogArea);
			loginMethods.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));
		}

		Composite handlerWidgets = new Composite(dialogArea, SWT.NONE);
		handlerWidgets.setLayout(new GridLayout());
		handlerWidgets.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		createCallbackHandlers(handlerWidgets);
		dialogArea.pack(true);
		return dialogArea;
	}

	private Composite createMethodSelector(Composite parent) {
		Composite methodsComposite = new Composite(parent, SWT.NONE);
		methodsComposite.setLayout(new RowLayout(SWT.HORIZONTAL));

		for (final String method : loginMethods) {
			final Button methodButton = new Button(methodsComposite, SWT.RADIO);
			methodButton.setSelection(method.equals(this.selectedMethod));
			methodButton.setText(method);
			methodButton.addSelectionListener(new SelectionAdapter() {
				@Override
				public void widgetSelected(SelectionEvent e) {
					if (methodButton.getSelection() && !method.equals(selectedMethod)) {
						setLoginData(CURRENT_METHOD_KEY, method);
						getShell().getDisplay().setData(CURRENT_METHOD_KEY, method);
						isCancelled = true;
						close();
					}
				}
			});
		}

		return methodsComposite;
	}

	private void createNameHandler(Composite composite, final NameCallback callback) {
		Label label = new Label(composite, SWT.NONE);
		label.setText(callback.getPrompt());
		final Text text = new Text(composite, SWT.SINGLE | SWT.LEAD | SWT.BORDER);
		text.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));
		text.addModifyListener(new ModifyListener() {

			public void modifyText(ModifyEvent event) {
				callback.setName(text.getText());
			}
		});
	}

	private void createPasswordHandler(Composite composite, final PasswordCallback callback) {
		Label label = new Label(composite, SWT.NONE);
		label.setText(callback.getPrompt());
		final Text passwordText = new Text(composite, SWT.SINGLE | SWT.LEAD | SWT.PASSWORD | SWT.BORDER);
		passwordText.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));
		passwordText.addModifyListener(new ModifyListener() {

			public void modifyText(ModifyEvent event) {
				callback.setPassword(passwordText.getText().toCharArray());
			}
		});
	}

	private void createRedirectMessage(Composite composite) {
		Label label = new Label(composite, SWT.NONE);
		label.setText("You are redirected to your identity provider.");
	}

	private void createTextInputHandler(Composite composite, final TextInputCallback callback) {
		final String attributeKey = "login.value:" + callback.getPrompt();

		Label label = new Label(composite, SWT.NONE);
		label.setText(callback.getPrompt());
		final Text text = new Text(composite, SWT.SINGLE | SWT.LEAD | SWT.BORDER);
		String lastText = getLoginData(attributeKey);
		final boolean[] remember = {lastText != null};
		if (lastText != null) {
			callback.setText(lastText);
			text.setText(lastText);
		}

		text.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));
		text.addModifyListener(new ModifyListener() {
			public void modifyText(ModifyEvent event) {
				callback.setText(text.getText());
				setLoginData(attributeKey, remember[0] ? text.getText() : null);
			}
		});

		if (callback.getPrompt().toLowerCase().contains("openid")) {
			label = new Label(composite, SWT.NONE);
			label.setText("Remember me");
			final Button rememberButton = new Button(composite, SWT.CHECK);
			rememberButton.setSelection(remember[0]);
			rememberButton.addSelectionListener(new SelectionAdapter() {
				@Override
				public void widgetSelected(SelectionEvent e) {
					remember[0] = rememberButton.getSelection();
					setLoginData(attributeKey, remember[0] ? text.getText() : null);
				}
			});

			Hyperlink loginWithGoogle = new Hyperlink(composite, SWT.NONE);
			loginWithGoogle.setText("Sign in with a Google Account");
			loginWithGoogle.addHyperlinkListener(new HyperlinkAdapter() {
				public void linkActivated(HyperlinkEvent e) {
					text.setText("https://www.google.com/accounts/o8/id");
					close();
				}
			});
		}
	}

	private void createTextoutputHandler(Composite composite, TextOutputCallback callback) {
		int messageType = callback.getMessageType();
		int dialogMessageType = IMessageProvider.NONE;
		switch (messageType) {
			case TextOutputCallback.INFORMATION:
				dialogMessageType = IMessageProvider.INFORMATION;
				break;
			case TextOutputCallback.WARNING:
				dialogMessageType = IMessageProvider.WARNING;
				break;
			case TextOutputCallback.ERROR:
				dialogMessageType = IMessageProvider.ERROR;
				break;
		}
		setMessage(callback.getMessage(), dialogMessageType);
	}

	protected final Callback[] getCallbacks() {
		return this.callbackArray;
	}

	protected Point getInitialSize() {
		return new Point(380, 280);
	}

	private String getLoginData(String key) {
		loadLoginData();
		return loginData.getProperty(key);
	}

	private String getLoginDataFromCookie() {
		String result = null;
		Cookie[] cookies = RWT.getRequest().getCookies();
		if (cookies != null) {
			for (int i = 0; result == null && i < cookies.length; i++) {
				Cookie cookie = cookies[i];
				if (COOKIE_NAME.equals(cookie.getName())) {
					return cookie.getValue();
				}
			}
		}
		return result;
	}

	@Override
	protected int getShellStyle() {
		return SWT.NO_TRIM | SWT.ON_TOP;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * javax.security.auth.callback.CallbackHandler#handle(javax.security.auth
	 * .callback.Callback[])
	 */
	public void handle(final Callback[] callbacks) throws IOException {
		this.callbackArray = callbacks;
		if (handleNonUserCallbacks()) {
			return;
		}

		openWithEventLoop();
	}

	private String getHostAndPath(HttpServletRequest request) {
		if ("http".equals(request.getScheme()) && request.getServerPort() == 80) {
			return "http://" + request.getServerName() + request.getContextPath();
		} else if ("https".equals(request.getScheme()) && request.getServerPort() == 443) {
			return "https://" + request.getServerName() + request.getContextPath();
		} else {
			return request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + request.getContextPath();
		}
	}

	private boolean handleNonUserCallbacks() throws IOException {
		Callback[] callbacks = getCallbacks();
		for (int i = 0; i < callbacks.length; i++) {
			Callback callback = callbacks[i];
			if (callback instanceof RedirectCallback) {
				redirectBrowser((RedirectCallback) callback);
				return true;
			} else if (callback instanceof RealmCallback) {
				StringBuffer contextUrl = RWT.getRequest().getRequestURL();
				((RealmCallback) callback).setContextUrl(getHostAndPath(RWT.getRequest()));
				((RealmCallback) callback).setApplicationUrl(contextUrl.toString());
				return true;
			} else if (callback instanceof ResponseCallback) {
				((ResponseCallback) callback).setResponseParameters(RWT.getRequest().getParameterMap());
				return true;
			} else if (callback instanceof RegisterCallback) {
				// TODO maybe add support for the register mode in RAP
				((RegisterCallback) callback).setRegister(false);
				return true;
			}
		}
		return false;
	}

	private void loadLoginData() {
		if (loginData == null) {
			loginDataModified = false;
			loginData = new Properties();
			String loginDataString = getLoginDataFromCookie();
			if (loginDataString != null) {
				try {
					loginData.load(new StringReader(loginDataString));
				} catch (IOException e) {
					// ignore
				}
			}
		}
	}

	@Override
	public void create() {
		super.create();

		Display display = getShell().getDisplay();
		Object imageDescData = display.getData("login.imageDescriptor");
		if (imageDescData instanceof ImageDescriptor) {
			final Image titleImage = ((ImageDescriptor) imageDescData).createImage(false, display);
			if (titleImage != null) {
				setTitleImage(titleImage);
				getShell().addDisposeListener(new DisposeListener() {
					@Override
					public void widgetDisposed(DisposeEvent event) {
						setTitleImage(null);
						titleImage.dispose();
					}
				});
			}
		}
	}

	protected void openWithEventLoop() throws IOException {
		final IOException[] exception = {null};
		final Display display = Display.getDefault();
		display.syncExec(new Runnable() {
			public void run() {
				isCancelled = false;

				// choose correct login method
				loginMethods = new ArrayList<String>();
				Object methodsData = display.getData(METHODS_KEY);
				if (methodsData instanceof Object[]) {
					for (Object method : (Object[]) methodsData) {
						loginMethods.add(method.toString());
					}
				} else if (methodsData instanceof Iterable<?>) {
					for (Object method : (Iterable<?>) methodsData) {
						loginMethods.add(method.toString());
					}
				}

				if (!loginMethods.isEmpty()) {
					selectedMethod = (String) display.getData(CURRENT_METHOD_KEY);
					String methodFromCookie = getLoginData(CURRENT_METHOD_KEY);
					if (methodFromCookie != null && !methodFromCookie.equals(selectedMethod) && loginMethods.contains(methodFromCookie)) {
						if (display.getData("login.canceled") == null) {
							// use same login method as for the last login
							selectedMethod = methodFromCookie;
							setLoginData(CURRENT_METHOD_KEY, selectedMethod);
							display.setData(CURRENT_METHOD_KEY, selectedMethod);
							isCancelled = true;
						} else {
							// store correct login method in cookie if changed
							// by user
							setLoginData(CURRENT_METHOD_KEY, selectedMethod);
						}
					}
				}

				if (!isCancelled) {
					setBlockOnOpen(false);
					open();

					String title = (String) display.getData("login.title");
					if (title != null) {
						setTitle(title);
					}

					Shell shell = getShell();

					shell.setMinimumSize(400, 300);
					shell.pack();

					while (shell != null && !shell.isDisposed()) {
						try {
							if (!display.readAndDispatch()) {
								display.sleep();
							}
						} catch (Throwable e) {
							if (e instanceof ThreadDeath) {
								display.setData("login.exception.threaddeath", e);
							}
							exception[0] = new IOException(e);
							return;
						}
					}
					if (!display.isDisposed()) {
						display.update();
					}
				}

				display.setData("login.canceled", isCancelled);
				if (isCancelled) {
					exception[0] = new IOException("Login canceled.");
				}
			}
		});

		storeLoginDataToCookie();

		if (exception[0] != null) {
			throw exception[0];
		}
	}

	private void redirectBrowser(final RedirectCallback callback) throws IOException {
		RWT.getClient().getService(JavaScriptExecutor.class).execute("parent.window.location.href=\"" + callback.getRedirectTo() + "\";");
		openWithEventLoop();
	}

	private void setLoginData(String key, String value) {
		loadLoginData();
		if (value != null) {
			loginData.setProperty(key, value);
		} else {
			loginData.remove(key);
		}
		loginDataModified = true;
	}

	private void storeLoginDataToCookie() {
		if (loginData != null && loginDataModified) {
			StringWriter writer = new StringWriter();
			try {
				loginData.store(writer, "");
			} catch (IOException e) {
				// ignore
			}
			Cookie cookie = new Cookie(COOKIE_NAME, writer.toString());
			cookie.setSecure(RWT.getRequest().isSecure());
			cookie.setMaxAge(COOKIE_MAX_AGE_SEC);
			RWT.getResponse().addCookie(cookie);
			loginDataModified = false;
		}
	}
}
