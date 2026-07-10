package net.enilink.platform.core;

import net.enilink.komma.core.IStatement;
import net.enilink.komma.core.Statement;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;
import net.enilink.komma.core.visitor.IDataVisitor;
import net.enilink.komma.em.util.KommaCoreModule;
import net.enilink.komma.model.*;
import net.enilink.komma.model.base.ExtensibleURIConverter;
import net.enilink.komma.model.base.URIHandler;
import net.enilink.platform.core.security.ISecureEntity;
import net.enilink.platform.core.security.SecurityUtil;
import net.enilink.platform.security.auth.EnilinkPrincipal;
import net.enilink.vocab.rdf.RDF;
import org.junit.Assert;
import org.junit.Test;

import javax.security.auth.Subject;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Map;

public class ModelSetManagerTest {
	@Test
	public void testCreateMetadataAndDataModelSets() {
		// add handler for platform: URIs which normally would only work within an OSGi
		// container using the KOMMA workbench bundle
		ExtensibleURIConverter.registerSharedUriHandler(new URIHandler() {
			@Override
			public boolean canHandle(URI uri) {
				return uri.isPlatform();
			}

			@Override
			protected URL getURL(URI uri) {
				// drop /plugin/[PLUGIN NAME]/ segments
				var path = uri.segmentsList().subList(2, uri.segmentsList().size());
				var pathString = "/" + String.join("/", path);
				return getClass().getResource(pathString);
			}
		});

		URI metadataModelSet = URIs.createURI("urn:enilink:metadata");
		URI dataModelSet = URIs.createURI("urn:enilink:data");
		URI memoryModelSetType = MODELS.NAMESPACE_URI.appendLocalPart("MemoryModelSet");

		ConfigHashGraph config = new ConfigHashGraph();
		config.load();
		config.add(metadataModelSet, RDF.PROPERTY_TYPE, memoryModelSetType);
		config.add(dataModelSet, RDF.PROPERTY_TYPE, memoryModelSetType);

		ModelSetManager manager = new ModelSetManager();
		IModelSet metaModelSet = manager.createMetaModelSet(config);
		IModelSet data = null;

		try {
			IModel metadataModel = metaModelSet.createModel(metadataModelSet);
			Assert.assertNotNull(metadataModel);

			data = manager.createModelSet(config, metadataModel);
			Assert.assertNotNull(data);

			IModel testModel = data.createModel(URIs.createURI("test:model"));
			testModel.setLoaded(true);
			// set ACL to allow access below
			((ISecureEntity) testModel).setAclOwner(SecurityUtil.UNKNOWN_USER);
			Assert.assertEquals(data, testModel.getModelSet());

			var stmt = new Statement(URIs.createURI("test:subject"), URIs.createURI("test:predicate"), URIs.createURI("test:object"));
			testModel.getManager().add(stmt);
			Assert.assertTrue(testModel.getManager().hasMatch(stmt.getSubject(), stmt.getPredicate(), stmt.getObject()));
		} finally {
			if (data != null) {
				data.dispose();
			}
			metaModelSet.dispose();
		}
	}
}