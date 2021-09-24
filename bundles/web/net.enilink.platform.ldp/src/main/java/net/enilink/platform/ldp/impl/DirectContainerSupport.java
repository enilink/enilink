package net.enilink.platform.ldp.impl;

import com.google.common.collect.ImmutableSet;
import net.enilink.composition.annotations.Precedes;
import net.enilink.composition.traits.Behaviour;
import net.enilink.komma.core.*;
import net.enilink.komma.model.IModel;
import net.enilink.komma.rdf4j.RDF4JValueConverter;
import net.enilink.platform.ldp.*;
import net.enilink.platform.ldp.config.ContainerHandler;
import net.enilink.platform.ldp.config.DirectContainerHandler;
import net.enilink.platform.ldp.config.Handler;
import net.enilink.platform.ldp.config.RdfResourceHandler;
import net.enilink.vocab.rdf.Property;
import net.enilink.vocab.rdf.RDF;
import net.enilink.vocab.xmlschema.XMLSCHEMA;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;

@Precedes(RdfSourceSupport.class)
public abstract class DirectContainerSupport implements LdpDirectContainer, Behaviour<LdpDirectContainer> {
	@Override
	public IReference getRelType() {
		return LDP.TYPE_DIRECTCONTAINER;
	}

	@Override
	public Set<IReference> getTypes() {
		return ImmutableSet.of(LDP.TYPE_CONTAINER, LDP.TYPE_DIRECTCONTAINER);
	}

	@Override
	public OperationResponse update(ReqBodyHelper body, Handler handler) {
		Set<IStatement> configStmts = null;
		if (body != null && handler != null & (body.isDirectContainer() || handler instanceof DirectContainerHandler) && !body.isBasicContainer() && body.isNoContains()) {
			URI resourceUri = body.getURI();
			IEntityManager manager = getEntityManager();
			Property memberRel = hasMemberRelation();
			LdpResource memberSrc = membershipResource();
			configStmts = matchDirectContainerConfig((DirectContainerHandler) handler, resourceUri);
			manager.removeRecursive(resourceUri, true);
			manager.add(new Statement(resourceUri, RDF.PROPERTY_TYPE, LDP.TYPE_DIRECTCONTAINER));
			hasMemberRelation(memberRel);
			membershipResource(memberSrc);
			configStmts.forEach(stmt -> manager.add(stmt));
			RDF4JValueConverter valueConverter = ReqBodyHelper.valueConverter();
			body.getRdfBody().forEach(stmt -> {
				IReference subj = valueConverter.fromRdf4j(stmt.getSubject());
				IReference pred = valueConverter.fromRdf4j(stmt.getPredicate());
				IValue obj = valueConverter.fromRdf4j(stmt.getObject());
				boolean acceptable = !(subj == resourceUri && body.isServerProperty(pred)) &&
						!(handler instanceof DirectContainerHandler && (pred == LDP.PROPERTY_HASMEMBERRELATION) || (pred == LDP.PROPERTY_MEMBERSHIPRESOURCE && memberSrc != null));

				if (acceptable)
					manager.add(new Statement(subj, pred, obj));
			});
			manager.add(new Statement(resourceUri, LDP.DCTERMS_PROPERTY_MODIFIED,
					new Literal(Instant.now().toString(), XMLSCHEMA.TYPE_DATETIME)));
			return new OperationResponse(OperationResponse.OK, "");
		}
		return new OperationResponse(OperationResponse.CONFLICT, " the resource to be modified is direct container, couldn't be replaced with resource of another type . ");
	}

	private Set<IStatement> matchDirectContainerConfig(DirectContainerHandler handler, URI resourceUri) {
		Set<IStatement> stmts = matchConfig(handler, resourceUri);
		URI menbership = handler.getMembership();
		LdpResource mebershipSrc = membershipResource();
		if (null != menbership && null != mebershipSrc)
			stmts.addAll(Arrays.asList(
					new Statement(resourceUri, LDP.PROPERTY_HASMEMBERRELATION, menbership),
					new Statement(resourceUri, LDP.PROPERTY_MEMBERSHIPRESOURCE, mebershipSrc.getURI())));
		return stmts;
	}

	@Override
	public OperationResponse createResource(IModel model, URI resourceType, RdfResourceHandler resourceHandler, ContainerHandler containerHandler, ReqBodyHelper body) {
		System.out.println("going to create resource in DC: " + getURI());
		//getEntityManager().add(new Statement(getURI(), LDP.PROPERTY_CONTAINS, body,getURI()));
		URI membershipSrc = membershipResource() != null ? membershipResource().getURI() : null;
		URI membership = null;
		if (containerHandler instanceof DirectContainerHandler) {
			DirectContainerHandler dh = (DirectContainerHandler) containerHandler;
			RdfResourceHandler memSrcConfig = dh.getRelSource();
			if (membership == null && memSrcConfig != null && memSrcConfig.getAssignedTo() != null)
				membershipSrc = memSrcConfig.getAssignedTo();
			else if (membership == null) membershipSrc = parentUri();
			membership = hasMemberRelation() != null ? hasMemberRelation().getURI() : dh.getMembership();
		}
		if (null != membershipSrc && null != membership) {
			//getEntityManager().find(membershipSrc).getEntityManager().add(new Statement(membershipSrc, membership, body.getURI()));
			String msg = "membership src: " + membershipSrc + " membership: " + membership;
			System.out.println(msg);
			model.getModelSet().getModel(membershipSrc, true).getManager().add(new Statement(membershipSrc, membership, body.getURI()));
			LdpRdfSource resource = model.getManager().findRestricted(body.getURI(), LdpRdfSource.class);
			resource.setContainer(this);
			return new OperationResponse(OperationResponse.OK, msg);
		} else
			return new OperationResponse(OperationResponse.UNSUPP_MEDIA, "not valid body entity or configuration fault");
	}

	private URI parentUri() {
		URI requestedUri = getURI();
		if (requestedUri.segmentCount() > 1 && requestedUri.toString().endsWith("/"))
			return requestedUri.trimSegments(2).appendSegment("");
		else if (requestedUri.segmentCount() > 0)
			return requestedUri.trimSegments(1).appendSegment("");
		else return null;
	}
}
