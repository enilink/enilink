package net.enilink.platform.ldp.impl;

import com.google.common.collect.ImmutableSet;
import net.enilink.composition.annotations.Precedes;
import net.enilink.composition.traits.Behaviour;
import net.enilink.komma.core.*;
import net.enilink.platform.ldp.LDP;
import net.enilink.platform.ldp.LdpBasicContainer;
import net.enilink.platform.ldp.ReqBodyHelper;
import net.enilink.platform.ldp.config.BasicContainerHandler;
import net.enilink.platform.ldp.config.Handler;
import net.enilink.vocab.rdf.RDF;
import net.enilink.vocab.xmlschema.XMLSCHEMA;

import java.time.Instant;
import java.util.Set;

@Precedes(RdfSourceSupport.class)
public abstract class BasicContainerSupport implements LdpBasicContainer, Behaviour<LdpBasicContainer> {

	@Override
	public IReference getRelType() {
		return LDP.TYPE_BASICCONTAINER;
	}

	@Override
	public Set<IReference> getTypes() {
		return ImmutableSet.of(LDP.TYPE_CONTAINER, LDP.TYPE_BASICCONTAINER);
	}

	@Override
	public OperationResponse update(ReqBodyHelper body, Handler config) {
		Set<IStatement> configStmts = null;
		if (null != body && null != config && !body.isDirectContainer() && body.isNoContains()) {
			//replace
			//FIXME use mapping
			URI uri = body.getURI();
			String msg = "";
			if (!(config instanceof BasicContainerHandler))
				msg = "wrong configurations, the resource is basic container but configured as not basic container. configuration will be ignored";
			else configStmts = matchConfig((BasicContainerHandler) config, uri);
			getEntityManager().removeRecursive(uri, true);
			//add statements resulting from the configuration
			getEntityManager().add(new Statement(uri, RDF.PROPERTY_TYPE, LDP.TYPE_BASICCONTAINER));
			if (null != configStmts) configStmts.forEach(stmt -> getEntityManager().add(stmt));
			body.getRdfBody().forEach(stmt -> {
				IReference subj = ReqBodyHelper.valueConverter().fromRdf4j(stmt.getSubject());
				IReference pred = ReqBodyHelper.valueConverter().fromRdf4j(stmt.getPredicate());
				IValue obj = ReqBodyHelper.valueConverter().fromRdf4j(stmt.getObject());
				if (subj != uri || !body.isServerProperty(pred))
					getEntityManager().add(new Statement(subj, pred, obj));
			});
			getEntityManager().add(new Statement(uri, LDP.DCTERMS_PROPERTY_MODIFIED,
					new Literal(Instant.now().toString(), XMLSCHEMA.TYPE_DATETIME)));
			return new OperationResponse(OperationResponse.OK, msg);
		}
		return new OperationResponse(OperationResponse.CONFLICT, "the resource to be modified is Basic Container and should be replaced with basic container");
	}
}
