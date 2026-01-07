package net.enilink.platform.core;

import java.io.InputStream;

import net.enilink.komma.core.IStatement;
import net.enilink.komma.core.visitor.IDataVisitor;
import net.enilink.komma.model.ModelUtil;
import org.junit.Assert;
import org.junit.Test;

public class ConfigHashGraphTest {
	@Test
	public void testQueryTimeoutParsedAsNumber() throws Exception {
		ConfigHashGraph cfg = new ConfigHashGraph();
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("config-with-timeout.ttl")) {
			Assert.assertNotNull("Test resource 'config-with-timeout.ttl' not found on classpath", in);
			ModelUtil.readData(in, "config-with-timeout.ttl", ModelUtil.mimeType("config-with-timeout.ttl"),
					new IDataVisitor<Void>() {
						@Override
						public Void visitBegin() {
							return null;
						}

						@Override
						public Void visitEnd() {
							return null;
						}

						@Override
						public Void visitStatement(IStatement stmt) {
							cfg.add(stmt);
							return null;
						}
					});
		}

		Object timeoutValue = cfg.filter(ConfigVocabulary.NAMESPACE_URI, ConfigVocabulary.QUERY_TIMEOUT, null)
				.objectInstance();
		Assert.assertNotNull("Timeout value should be present", timeoutValue);
		Assert.assertTrue("Timeout value should be a number", timeoutValue instanceof Number);
		Assert.assertEquals(1234L, ((Number) timeoutValue).longValue());
	}
}
