package net.enilink.platform.workbench.components;

import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import org.apache.felix.http.jakartawrappers.HttpServletRequestWrapper;
import org.apache.felix.http.jakartawrappers.HttpServletResponseWrapper;
import org.apache.felix.http.javaxwrappers.ServletWrapper;
import org.eclipse.rap.service.http.HttpContext;
import org.eclipse.rap.service.http.NamespaceException;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.http.HttpService;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URL;
import java.util.Dictionary;
import java.util.Objects;

/**
 * Bridge component for RAP's custom HttpService interface.
 *
 * RAP expects an instance of org.eclipse.rap.service.http.HttpService published as OSGi service.
 * This is used to register RWTServlet and resources.
 */
@Component(immediate = true)
public class RapHttpService implements org.eclipse.rap.service.http.HttpService {
	static class RapOsgiHttpContextWrapper implements org.osgi.service.http.HttpContext {
		final HttpContext delegate;

		RapOsgiHttpContextWrapper(HttpContext delegate) {
			this.delegate = delegate;
		}

		@Override
		public boolean handleSecurity(HttpServletRequest request, HttpServletResponse response) throws IOException {
			return delegate.handleSecurity(new HttpServletRequestWrapper(request), new HttpServletResponseWrapper(response));
		}

		@Override
		public URL getResource(String name) {
			return delegate.getResource(name);
		}

		@Override
		public String getMimeType(String name) {
			return delegate.getMimeType(name);
		}

		@Override
		public boolean equals(Object o) {
			if (o == null || getClass() != o.getClass()) return false;
			RapOsgiHttpContextWrapper that = (RapOsgiHttpContextWrapper) o;
			return Objects.equals(delegate, that.delegate);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(delegate);
		}
	}

	static class OsgiRapHttpContextWrapper implements HttpContext {
		final org.osgi.service.http.HttpContext delegate;

		OsgiRapHttpContextWrapper(org.osgi.service.http.HttpContext delegate) {
			this.delegate = delegate;
		}

		@Override
		public boolean handleSecurity(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) throws IOException {
			return delegate.handleSecurity(new org.apache.felix.http.javaxwrappers.HttpServletRequestWrapper(request), new org.apache.felix.http.javaxwrappers.HttpServletResponseWrapper(response));
		}

		@Override
		public URL getResource(String name) {
			return delegate.getResource(name);
		}

		@Override
		public String getMimeType(String name) {
			return delegate.getMimeType(name);
		}

		@Override
		public boolean equals(Object o) {
			if (o == null || getClass() != o.getClass()) return false;
			OsgiRapHttpContextWrapper that = (OsgiRapHttpContextWrapper) o;
			return Objects.equals(delegate, that.delegate);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(delegate);
		}
	}

	@Reference
	protected HttpService httpService;

	@Override
	public void registerServlet(String alias, Servlet servlet, Dictionary<?, ?> initparams, HttpContext context) throws ServletException, NamespaceException {
		try {
			httpService.registerServlet(alias, new ServletWrapper(servlet), initparams, new RapOsgiHttpContextWrapper(context));
		} catch (javax.servlet.ServletException e) {
			throw new ServletException(e);
		} catch (org.osgi.service.http.NamespaceException e) {
			throw new NamespaceException(e.getMessage());
		}
	}

	@Override
	public void registerResources(String alias, String name, HttpContext context) throws NamespaceException {
		try {
			httpService.registerResources(alias, name, new RapOsgiHttpContextWrapper(context));
		} catch (org.osgi.service.http.NamespaceException e) {
			throw new NamespaceException(e.getMessage());
		}
	}

	@Override
	public void unregister(String alias) {
		httpService.unregister(alias);
	}

	@Override
	public HttpContext createDefaultHttpContext() {
		return new OsgiRapHttpContextWrapper(httpService.createDefaultHttpContext());
	}
}
