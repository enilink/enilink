package net.enilink.platform.ldp.impl;

import net.enilink.composition.traits.Behaviour;
import net.enilink.komma.core.*;
import net.enilink.komma.model.IModel;
import net.enilink.komma.rdf4j.RDF4JValueConverter;
import net.enilink.platform.ldp.LDP;
import net.enilink.platform.ldp.LdpContainer;
import net.enilink.platform.ldp.ReqBodyHelper;
import net.enilink.platform.ldp.config.BasicContainerHandler;
import net.enilink.platform.ldp.config.ContainerHandler;
import net.enilink.platform.ldp.config.DirectContainerHandler;
import net.enilink.platform.ldp.config.RdfResourceHandler;
import net.enilink.vocab.rdf.RDF;
import net.enilink.vocab.xmlschema.XMLSCHEMA;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

public abstract class ContainerSupport implements LdpContainer, Behaviour<LdpContainer> {
    @Override
    //FIXME unnecessary parameter containerURI
    public Map<Boolean, String> createResource(IModel model, URI resourceType, RdfResourceHandler resourceHandler, ContainerHandler ch, ReqBodyHelper body){
        if(body == null || body.getRdfBody() == null) return Collections.singletonMap(false, "no RDF-body content found");
        RdfResourceHandler conf = resourceHandler != null ? resourceHandler : new RdfResourceHandler();
        boolean configuredAsBC = conf instanceof BasicContainerHandler;
        boolean configuredAsDC = conf instanceof DirectContainerHandler &&
                ((DirectContainerHandler) conf).getMembership() != null;
        boolean valid = false;
        if (resourceType.equals(LDP.TYPE_BASICCONTAINER)) valid =  body.isBasicContainer() || configuredAsBC;
        else if (resourceType.equals(LDP.TYPE_DIRECTCONTAINER)) valid = (body.isDirectContainer() && !body.isBasicContainer()) || configuredAsDC;
        else if (resourceType.equals(LDP.TYPE_RDFSOURCE)) valid = !body.isDirectContainer() && !body.isBasicContainer();

        URI resourceUri = body.getURI();
        IModel resourceModel = null;
        if (conf.isSeparateModel() || conf instanceof ContainerHandler)
            resourceUri = resourceUri.appendSegment("");
        if (valid) {
            model.getModelSet().getUnitOfWork().begin();
            try {
                if (!conf.isSeparateModel())
                    resourceModel = model;
                else {
                    IModel m = model.getModelSet().createModel(resourceUri);
                    m.setLoaded(true);
                    resourceModel =m;
                }
                IEntityManager resourceManager = resourceModel.getManager();
                resourceManager.add(Arrays.asList(
                        new Statement(resourceUri, RDF.PROPERTY_TYPE, resourceType),
                        new Statement(resourceUri, LDP.DCTERMS_PROPERTY_CREATED, new Literal(Instant.now().toString(), XMLSCHEMA.TYPE_DATETIME))));
                RDF4JValueConverter valueConverter = body.valueConverter();
                //add server-managed properties
                final URI finalResourceUri = resourceUri;
                body.getRdfBody().forEach(stmt -> {
                    IReference subj = valueConverter.fromRdf4j(stmt.getSubject());
                    IReference pred = valueConverter.fromRdf4j(stmt.getPredicate());
                    IValue obj = valueConverter.fromRdf4j(stmt.getObject());
                    //ignore server-managed properties and configured properties
                    boolean conflict = configuredAsDC && (pred == LDP.PROPERTY_HASMEMBERRELATION || pred == LDP.PROPERTY_MEMBERSHIPRESOURCE);
                    if (!(subj == finalResourceUri && body.isServerProperty(pred)) && !conflict)
                        resourceManager.add(new Statement(subj, pred, obj));
                });
                // handle configuration
                conf.getTypes().forEach(t -> resourceManager.add(new Statement(finalResourceUri, RDF.PROPERTY_TYPE, t)));
                // if resource to be created was configured to be membership resource for a direct container
                DirectContainerHandler dh = conf.getDirectContainerHandler();
                if (null != dh) {
                    // if the new Resource to be created was configured to be Relationship Source
                    // for a direct container, this direct container will be also created.
                    // it should be no previously created direct containers whose relationship source does
                    // not exist(assignable = null or ignored)
                    // for this reason the two resources shall be created in the same model
                    URI dc;
                    if (resourceUri.toString().endsWith("/"))  dc = resourceUri.appendLocalPart(dh.getName()).appendSegment("");
                    else dc = resourceUri.appendSegment(dh.getName()).appendSegment("");
                    resourceManager.add(Arrays.asList(
                            new Statement(dc, RDF.PROPERTY_TYPE, LDP.TYPE_DIRECTCONTAINER),
                            new Statement(dc, LDP.PROPERTY_HASMEMBERRELATION, dh.getMembership()),
                            new Statement(dc, LDP.PROPERTY_MEMBERSHIPRESOURCE, resourceUri)));
                }
                //if resource was configured to be Direct Container with certain configs take it
                String msg = "" ;
                if (conf instanceof DirectContainerHandler) {
                    DirectContainerHandler relHandler = (DirectContainerHandler) conf;
                    if (relHandler.getRelSource() != null && relHandler.getRelSource().getAssignedTo() != null) {
                        URI membershipResource;
                        if((DirectContainerHandler.SELF.equals(relHandler.getRelSource().getAssignedTo())))
                            // special case when a container points to itself as resource
                            membershipResource = resourceUri;
                        else membershipResource = relHandler.getRelSource().getAssignedTo();
                        resourceManager.add(Arrays.asList(
                                new Statement(resourceUri, LDP.PROPERTY_HASMEMBERRELATION, relHandler.getMembership()),
                                new Statement(resourceUri, LDP.PROPERTY_MEMBERSHIPRESOURCE, membershipResource)));
                    } else msg="DirectContainerHandler not configured correctly";
                }
                getEntityManager().add(new Statement(getURI(), LDP.PROPERTY_CONTAINS, body.getURI()));
                return Collections.singletonMap(true,msg);
            } catch( Throwable t) {
                t.printStackTrace();
                return Collections.singletonMap(false, t.getMessage());
            } finally {
                model.getModelSet().getUnitOfWork().end();
            }
        } else {
            return Collections.singletonMap(false,"invalid or incomplete RDF content");
        }
    }
}
