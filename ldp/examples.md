We want to configure LDP-service in enilik for [Digital Object Memory](https://www.w3.org/2005/Incubator/omm/XGR-omm-20111026/) and in such way that considers these criteria 
- root container should contain elements of typ memory: http://www.w3.org/2005/Incubator/omm/elements/1.0/memory/
- each memory references (RelationResource) a resource of type directContainer with a name "toc"
- toc should contain elements of type block:http://www.w3.org/2005/Incubator/omm/elements/1.0/elemnt/ and not deletable
- for this example block can be of any resource type (it can be further parameterized)

## Configuration definition:

    def createHandler:BasicContainerHandler = {
        val toc = new DirectContainerHandler().
                       withtName("toc").
                       withMembership(OMM.appendLocalPart("element")).
                       withDeleteable(false).asInstanceOf[DirectContainerHandler]              
        val memory = new RdfResourceHandler().
                      withTypes( List(OMM.appendLocalPart("memory")).asJava).
                      withMembershipRelSrcFor(toc)
        new BasicContainerHandler().withContainsHandler(memory).withDeleteable(false).asInstanceOf[BasicContainerHandler]    
    }
    
Or using concept classes for different cases:
For example in case study of  Memory Object : 

     val handler = BasicContainerHandler.fromConcept(classOf[Memory])
     register(handler.getPath, rootUri, handler)
Memory can be defined and configured like this:

    @BasicContainer("DOM")
    @Iri("http://www.w3.org/2005/Incubator/omm/elements/1.0/memory")
    public interface Memory extends LdpRdfSource {   
    	@Iri("http://www.w3.org/2005/Incubator/omm/elements/1.0/primaryID")
    	public String primaryID();
    	public void primaryID(String primaryID);    
    	@Iri("http://www.w3.org/ns/prov#generatedAtTime")
    	public Date generatedAt();
    	public void generatedAt(Date generatedAt);
    
    	// OMM: omm:toc -> omm:element
    	@DirectContainer("toc")
    	@Iri("http://www.w3.org/2005/Incubator/omm/elements/1.0/element")
    	public Set<Block> elements();
    	}

**Note**: if no configuration provided then the default configuration will be considered

## 1. POST 
    curl -v -X POST -d '<> rdfs:label "Memory Object" .' -H "Content-Type: text/turtle" -H "Slug: test" http://localhost:10080/DOM/
the response:

    < HTTP/1.1 201 Created
    < Date: Wed, 03 Mar 2021 11:27:25 GMT
    < Set-Cookie: JSESSIONID=node01uc1wpienz8bm1i5n7xom4qsts3.node0; Path=/
    < Expires: Wed, 3 Mar 2021 11:27:25 GMT
    < Location: http://localhost:10080/DOM/test4/
    < Allow: OPTIONS, HEAD, GET, POST, PUT, DELETE
    < Accept-Post: */*
    < Cache-Control: no-cache, private, no-store
    < Content-Type: text/plain;charset=utf-8
    < Pragma: no-cache
    < Date: Wed, 3 Mar 2021 11:27:25 GMT
    < Link: <http://www.w3.org/ns/ldp#RDFSource>;rel=type

**Notes**:
- server adds additional unique part to the suggested name in the slug (to keep unique resource naming, in this example 4) and omits all non alphanumeric characters or replaces them with '-'. please see "location" header in response.
- if no suggested name (no slug header) was provided then the resource will be named with default name "resource"+<unique  number>: for example resource4
- the server will add all necessary statements resulting from the configuration or implementation considerations or server managed properties.
to see the resulting see (2. GET) below.
- it's possible to determine the resource type in POST-Header (for example: Link: <http://www.w3.org/ns/ldp#BasicContainer>; rel="type"), default is ldp#RDFSource
- failed on case of conflict (for example: server was asked to create DirectContainer without providing RelationResource triple in Post.body. in this case returned status code 415 with a message of not valid content.
##
    curl -v -X POST -H 'Link: <http://www.w3.org/ns/ldp#DirectContainer>; rel="type"' -H "Content-Type: text/turtle"  http://localhost:10080/DOM/
the response: 

    < HTTP/1.1 415 Unsupported Media Type
    < Date: Wed, 03 Mar 2021 14:25:12 GMT
    < Set-Cookie: JSESSIONID=node0nxqu0vie5rifgmnk4c2h63ne12.node0; Path=/
    < Expires: Wed, 3 Mar 2021 14:25:12 GMT
    < Allow: OPTIONS, HEAD, GET, POST, PUT, DELETE
    < Cache-Control: no-cache, private, no-store
    < Content-Type: text/plain;charset=utf-8
    < Pragma: no-cache
    < Date: Wed, 3 Mar 2021 14:25:12 GMT
    < Link: <https://www.w3.org/TR/ldp/>;rel="http://www.w3.org/ns/ldp#constrainedBy" 
    < X-Lift-Version: Unknown Lift Version
    < Content-Length: 49
    < Server: Jetty(9.4.27.v20200227)
    < 
    * Connection #0 to host localhost left intact
    not valid body entity or violation of constraints

## 2. GET (acquiring the previously created resource: test4)
    curl -v -H "Content-Type: text/turtle"  http://localhost:10080/DOM/test4/
the response:

    < HTTP/1.1 200 OK
    < Date: Wed, 03 Mar 2021 13:54:10 GMT
    < Set-Cookie: JSESSIONID=node01kvn5hu0jn1fgjerlm29an2at9.node0; Path=/
    < Expires: Wed, 3 Mar 2021 13:54:10 GMT
    < Allow: OPTIONS, HEAD, GET, POST, PUT, DELETE
    < Accept-Post: */*
    < Cache-Control: no-cache, private, no-store
    < Content-Type: text/turtle
    < Pragma: no-cache
    < Date: Wed, 3 Mar 2021 13:54:10 GMT
    < ETag: W/"1614779640000-685"
    < Link: <http://www.w3.org/ns/ldp#RDFSource>;rel=type, <http://www.w3.org/ns/ldp#Resource>;rel=type
    < X-Lift-Version: Unknown Lift Version
    < Content-Length: 602
    < Server: Jetty(9.4.27.v20200227)
    < 
    @prefix ldp: <http://www.w3.org/ns/ldp#> .
    @prefix dcterms: <http://purl.org/dc/terms/> .
    @prefix omm: <http://www.w3.org/2005/Incubator/omm/elements/1.0/> .
    @prefix prov: <http://www.w3.org/ns/prov#> .
    <http://localhost:10080/DOM/test4/> <http://purl.org/dc/terms/created> "2021-03-03T11:27:25.411Z"^^<http://www.w3.org/2001/XMLSchema#dateTime>;
    a omm:memory,ldp:RDFSource;
    <http://www.w3.org/2000/01/rdf-schema#label> "Memory Object" .
    <http://localhost:10080/DOM/test4/toc/> a ldp:DirectContainer;
    ldp:hasMemberRelation omm:element;
    ldp:membershipResource <http://localhost:10080/DOM/test4/> .

**Notes**: - the header Content-Type in example can be omitted (the default value is text/turtle otherwise it should be provided)
       - GETing 

## 3. GET: acquiring the root container after creating resource test4
    curl -v http://localhost:10080/DOM/
The response:

    < HTTP/1.1 200 OK
    < Date: Wed, 03 Mar 2021 14:09:41 GMT
    < Set-Cookie: JSESSIONID=node09v45mwaw5fudymgt7emptuat11.node0; Path=/
    < Expires: Wed, 3 Mar 2021 14:09:41 GMT
    < Allow: OPTIONS, HEAD, GET, POST, PUT, DELETE
    < Accept-Post: */*
    < Cache-Control: no-cache, private, no-store
    < Content-Type: text/turtle
    < Pragma: no-cache
    < Date: Wed, 3 Mar 2021 14:09:41 GMT
    < ETag: W/"1614780540000"
    < Link: <http://www.w3.org/ns/ldp#BasicContainer>;rel=type, <http://www.w3.org/ns/ldp#Resource>;rel=type
    < X-Lift-Version: Unknown Lift Version
    < Content-Length: 617
    < Server: Jetty(9.4.27.v20200227)
    < 
    @prefix ldp: <http://www.w3.org/ns/ldp#> .
    @prefix dcterms: <http://purl.org/dc/terms/> .
    @prefix omm: <http://www.w3.org/2005/Incubator/omm/elements/1.0/> .
    @prefix prov: <http://www.w3.org/ns/prov#> .
    <http://localhost:10080/DOM/> a ldp:BasicContainer,ldp:Container,ldp:RDFSource,ldp:Resource;
    <http://www.w3.org/2000/01/rdf-schema#comment> "LDP container for Digital Object Memories";
    <http://www.w3.org/2000/01/rdf-schema#label> "LDP DOM container";
    ldp:contains <http://localhost:10080/DOM/resource8/>,<http://localhost:10080/DOM/test1/>,<http://localhost:10080/DOM/test3/>,<http://localhost:10080/DOM/test4/> .

## 4. POST: creating a Block 
    curl -v -X POST -d '<> rdfs:label "Block Object" .' -H "Content-Type: text/turtle" -H "Slug: block" http://localhost:10080/DOM/test4/toc/
the response:

    < HTTP/1.1 201 Created
    < Date: Thu, 04 Mar 2021 17:00:11 GMT
    < Set-Cookie: JSESSIONID=node01nmvl0jzniwa8zkdcs45elodz2.node0; Path=/
    < Expires: Thu, 4 Mar 2021 17:00:12 GMT
    < Location: http://localhost:10080/DOM/test1/toc/block12/
    < Allow: OPTIONS, HEAD, GET, POST, PUT, DELETE
    < Accept-Post: */*
    < Cache-Control: no-cache, private, no-store
    < Content-Type: text/plain;charset=utf-8
    < Pragma: no-cache
    < Date: Thu, 4 Mar 2021 17:00:12 GMT
    < Link: <http://www.w3.org/ns/ldp#RDFSource>;rel=type

**Note**: in our example we have not added any configuration to block (see configuration above), and the type of resource to be created is not provided by Link-Header in request, therefore is default type (ldp#RDFSource)

## 5. GET: acquiring new created block12
    curl -v http://localhost:10080/DOM/test4/toc/block12/
the response: 

    < HTTP/1.1 200 OK
    < Date: Thu, 04 Mar 2021 17:14:47 GMT
    < Set-Cookie: JSESSIONID=node0x8zlt9f0jq0lykgkl5vxm0ne3.node0; Path=/
    < Expires: Thu, 4 Mar 2021 17:14:47 GMT
    < Allow: OPTIONS, HEAD, GET, POST, PUT, DELETE
    < Accept-Post: */*
    < Cache-Control: no-cache, private, no-store
    < Content-Type: text/turtle
    < Pragma: no-cache
    < Date: Thu, 4 Mar 2021 17:14:47 GMT
    < ETag: W/"1614878087973"
    < Link: <http://www.w3.org/ns/ldp#Resource>;rel=type
    < X-Lift-Version: Unknown Lift Version
    < Content-Length: 203

    <http://localhost:10080/DOM/test1/toc/block12/> <http://purl.org/dc/terms/created> "2021-03-04T17:00:11.801Z"^^xsd:dateTime;
    a <http://www.w3.org/ns/ldp#RDFSource>;
    rdfs:label "Block Object" .

**Note**: Content-Type not provided in request. the default is text/turtle.

## 6. GETing the memory test4:
    < HTTP/1.1 200 OK
    < Date: Thu, 04 Mar 2021 17:40:34 GMT
    < Set-Cookie: JSESSIONID=node0591vz0xizle112do05duxi6bm4.node0; Path=/
    < Expires: Thu, 4 Mar 2021 17:40:34 GMT
    < Allow: OPTIONS, HEAD, GET, POST, PUT, DELETE
    < Accept-Post: */*
    < Cache-Control: no-cache, private, no-store
    < Content-Type: text/turtle
    < Pragma: no-cache
    < Date: Thu, 4 Mar 2021 17:40:34 GMT
    < ETag: W/"1614879634283-578"
    < Link: <http://www.w3.org/ns/ldp#RDFSource>;rel=type, <http://www.w3.org/ns/ldp#Resource>;rel=type
    < X-Lift-Version: Unknown Lift Version
    < Content-Length: 697
    < Server: Jetty(9.4.27.v20200227)
    < 
    @prefix ldp: <http://www.w3.org/ns/ldp#> .
    @prefix dcterms: <http://purl.org/dc/terms/> .
    @prefix omm: <http://www.w3.org/2005/Incubator/omm/elements/1.0/> .
    @prefix prov: <http://www.w3.org/ns/prov#> .
    <http://localhost:10080/DOM/test4/> dcterms:created "2021-03-04T17:00:03.556Z"^^<http://www.w3.org/2001/XMLSchema#dateTime>;
    a omm:memory,ldp:RDFSource;
    <http://www.w3.org/2000/01/rdf-schema#label> "Memory Object";
    omm:element <http://localhost:10080/DOM/test4/toc/block12/> .
    <http://localhost:10080/DOM/test4/toc/> a ldp:DirectContainer;
    ldp:contains <http://localhost:10080/DOM/test4/toc/block12/>;
    ldp:hasMemberRelation omm:element;
    ldp:membershipResource <http://localhost:10080/DOM/test4/> .

## 7. PUTing test4 with wrong ETag (for example empty ETag)
    curl -v -X PUT  -d '<> rdfs:label "new label" .' -H "Content-Type: text/turtle"  http://localhost:10080/DOM/test4/
the response: 

    < HTTP/1.1 412 Precondition Failed
    < Date: Fri, 05 Mar 2021 13:47:22 GMT
    < Set-Cookie: JSESSIONID=node016jt205sspc71j67yfrgff6y911.node0; Path=/
    < Expires: Fri, 5 Mar 2021 13:47:22 GMT
    < Allow: OPTIONS, HEAD, GET, POST, PUT, DELETE
    < Cache-Control: no-cache, private, no-store
    < Content-Type: text/plain;charset=utf-8
    < Pragma: no-cache
    < Date: Fri, 5 Mar 2021 13:47:22 GMT
    < Link: <http://www.w3.org/ns/ldp#Resource>;rel=type, <http://www.w3.org/ns/ldp#BasicContainer>;rel=type ,<https://www.w3.org/TR/ldp/>;rel="http://www.w3.org/ns/ldp#constrainedBy" 
    < X-Lift-Version: Unknown Lift Version
    < Content-Length: 37
    < Server: Jetty(9.4.27.v20200227)
    IF-Match, Avoiding mid-air collisions

## 8. Updating test4
    curl -v -X PUT -H 'If-Match:  W/"1614956459166-578"' -d '<> rdfs:label "new label" .' -H "Content-Type: text/turtle"  http://localhost:10080/DOM/test4/
the response:

    < HTTP/1.1 200 OK
    < Date: Fri, 05 Mar 2021 15:02:04 GMT
    < Set-Cookie: JSESSIONID=node0cn71f5ymkkua1krinq9pl3yqb3.node0; Path=/
    < Expires: Fri, 5 Mar 2021 15:02:04 GMT
    < Location: http://localhost:10080/DOM/test1/
    < Allow: OPTIONS, HEAD, GET, POST, PUT, DELETE
    < Accept-Post: */*
    < Cache-Control: no-cache, private, no-store
    < Content-Type: 
    < Pragma: no-cache
    < Date: Fri, 5 Mar 2021 15:02:04 GMT
    < ETag: W/"1614956524669-0"
    < Link: <http://www.w3.org/ns/ldp#RDFSource>;rel=type

## 9. GETing test4 after updating
    < HTTP/1.1 200 OK
    < Date: Fri, 05 Mar 2021 15:05:38 GMT
    < Set-Cookie: JSESSIONID=node0crvgxpql3rth29qlk1whiyl74.node0; Path=/
    < Expires: Fri, 5 Mar 2021 15:05:38 GMT
    < Allow: OPTIONS, HEAD, GET, POST, PUT, DELETE
    < Accept-Post: */*
    < Cache-Control: no-cache, private, no-store
    < Content-Type: text/turtle
    < Pragma: no-cache
    < Date: Fri, 5 Mar 2021 15:05:38 GMT
    < ETag: W/"1614956738401-203"
    < Link: <http://www.w3.org/ns/ldp#Resource>;rel=type
    < X-Lift-Version: Unknown Lift Version
    < Content-Length: 203
    < Server: Jetty(9.4.27.v20200227)
    < 
    @prefix : <http://localhost:10080/DOM/test1/> .
    @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
    @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
    @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
    @prefix owl: <http://www.w3.org/2002/07/owl#> .
    <http://localhost:10080/DOM/test4/> <http://purl.org/dc/terms/modified> "2021-03-05T16:35:58.523Z"^^xsd:dateTime;
    rdfs:label "new label";
    <http://www.w3.org/2005/Incubator/omm/elements/1.0/element> <http://localhost:10080/DOM/test3/toc/block12/> .
    <http://localhost:10080/DOM/test4/toc/> a <http://www.w3.org/ns/ldp#DirectContainer>;
    <http://www.w3.org/ns/ldp#contains> <http://localhost:10080/DOM/test1/toc/block12/>;
    <http://www.w3.org/ns/ldp#hasMemberRelation> <http://www.w3.org/2005/Incubator/omm/elements/1.0/element> .

**Notes**: the new body replaces the old. server-managed properties remain (types, configurations), elements (block12) are recreated from the related container. 
they will not be recreated if the container "toc" was previously deleted. in our example we can't remove it because it was configured to be undeleteable.
also, they will not be recreated if the resource to be modified was not previously configured as membership resource for a direct container.
 
## 10. Deleting toc (should fail because it was configured to be not deleteable)
    curl -v -X DELETE http://localhost:10080/DOM/test1/toc/
the response:
    
    < HTTP/1.1 422 Unprocessable Entity
    < Date: Thu, 04 Mar 2021 22:48:29 GMT
    < Set-Cookie: JSESSIONID=node0iwrjgafbsbyls33r0mtpym171.node0; Path=/
    < Expires: Thu, 4 Mar 2021 22:48:29 GMT
    < Allow: OPTIONS, HEAD, GET, POST, PUT, DELETE
    < Cache-Control: no-cache, private, no-store
    < Content-Type: text/plain;charset=utf-8
    < Pragma: no-cache
    < Date: Thu, 4 Mar 2021 22:48:29 GMT
    < Link: <https://www.w3.org/TR/ldp/>;rel="http://www.w3.org/ns/ldp#constrainedBy" 
    container configured not deleteable

## 11. POSTing to none container Resource
    curl -v -X POST -d '<> rdfs:label "Resource  Object" .' -H "Content-Type: text/turtle"  http://localhost:10080/DOM/test4/
The response:

    < HTTP/1.1 412 Precondition Failed
    < Date: Fri, 05 Mar 2021 13:25:41 GMT
    < Set-Cookie: JSESSIONID=node01s81u6x5ef0xzodv3ezla54mi1.node0; Path=/
    < Expires: Fri, 5 Mar 2021 13:25:41 GMT
    < Allow: OPTIONS, HEAD, GET, POST, PUT, DELETE
    < Cache-Control: no-cache, private, no-store
    < Content-Type: text/plain;charset=utf-8
    < Pragma: no-cache
    < Date: Fri, 5 Mar 2021 13:25:41 GMT
    < Link: <https://www.w3.org/TR/ldp/>;rel="http://www.w3.org/ns/ldp#constrainedBy" 
    none container resource shouldn't accept POST request

## 12. DELETing Block12
    curl -v -X DELETE http://localhost:10080/DOM/test4/toc/block12/
the response:

    < HTTP/1.1 200 OK
    < Date: Fri, 05 Mar 2021 13:34:06 GMT
    < Set-Cookie: JSESSIONID=node0rupu7zm94bp11mmvuk6swsvqw4.node0; Path=/
    < Expires: Fri, 5 Mar 2021 13:34:06 GMT
    < Allow: OPTIONS, HEAD, GET, POST, PUT, DELETE
    < Accept-Post: */*
    < Cache-Control: no-cache, private, no-store
    < Content-Type: 
    < Pragma: no-cache
    < Date: Fri, 5 Mar 2021 13:34:06 GMT
    < ETag: W/"1614951246351"
    < Link: <http://www.w3.org/ns/ldp#Resource>;rel=type

## 13 Creating new block of type Basic Container (just for example, should fail: the container was not configured to contain basic containers and body request does not include BasicContainer type)
    curl -v -X POST -d '<> rdfs:label "Block Object" .' -H "Content-Type: text/turtle" -H "Slug: block" -H 'Link: <http://www.w3.org/ns/ldp#BasicContainer>; rel="type"'  http://localhost:10080/DOM/test3/toc/
the response:

    < HTTP/1.1 415 Unsupported Media Type
    < Date: Sat, 06 Mar 2021 16:54:52 GMT
    < Set-Cookie: JSESSIONID=node01cyd30n0eppumfj0ctyt004pe10.node0; Path=/
    < Expires: Sat, 6 Mar 2021 16:54:52 GMT
    < Allow: OPTIONS, HEAD, GET, POST, PUT, DELETE
    < Cache-Control: no-cache, private, no-store
    < Content-Type: text/plain;charset=utf-8
    < Pragma: no-cache
    < Date: Sat, 6 Mar 2021 16:54:52 GMT
    < Link: <https://www.w3.org/TR/ldp/>;rel="http://www.w3.org/ns/ldp#constrainedBy" 
    < X-Lift-Version: Unknown Lift Version
    < Content-Length: 49
    < Server: Jetty(9.4.27.v20200227)
    < 
    not valid body entity or violation of constraints

## 14. to assure creating block of type the basic container:

    curl -v -X POST -d '<> a ldp:RDFSource, ldp:BasicContainer; rdfs:label "Block Object as basic container" .' -H "Content-Type: text/turtle" -H "Slug: block" -H 'Link: <http://www.w3.org/ns/ldp#BasicContainer>; rel="type"'  http://localhost:10080/DOM/test3/toc/
the response: 

    < HTTP/1.1 201 Created
    < Date: Sat, 06 Mar 2021 17:09:49 GMT
    < Set-Cookie: JSESSIONID=node019b1f6smjjli111w84by0dy5mk12.node0; Path=/
    < Expires: Sat, 6 Mar 2021 17:09:49 GMT
    < Location: http://localhost:10080/DOM/test3/toc/block10/
    < Allow: OPTIONS, HEAD, GET, POST, PUT, DELETE
    < Accept-Post: */*
    < Cache-Control: no-cache, private, no-store
    < Content-Type: text/plain;charset=utf-8
    < Pragma: no-cache
    < Date: Sat, 6 Mar 2021 17:09:49 GMT
    < Link: <http://www.w3.org/ns/ldp#BasicContainer>;rel=type

**Note**: to crate block of type direct container the req-body should have triples for type, membershipRelation und membershipResource if no configuration for that was provided.

## 15 GET 
    curl -v  http://localhost:10080/DOM/test3/block10/
the response

    < HTTP/1.1 200 OK
    < Date: Sat, 06 Mar 2021 17:12:22 GMT
    < Set-Cookie: JSESSIONID=node0iesgr7l0d1lz1vwdhkwyx5wf014.node0; Path=/
    < Expires: Sat, 6 Mar 2021 17:12:22 GMT
    < Allow: OPTIONS, HEAD, GET, POST, PUT, DELETE
    < Accept-Post: */*
    < Cache-Control: no-cache, private, no-store
    < Content-Type: text/turtle
    < Pragma: no-cache
    < Date: Sat, 6 Mar 2021 17:12:22 GMT
    < ETag: W/"1615050742981-203"
    < Link: <http://www.w3.org/ns/ldp#Resource>;rel=type
    
    @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
    @prefix owl: <http://www.w3.org/2002/07/owl#> .
    <http://localhost:10080/DOM/test3/toc/block10/> <http://purl.org/dc/terms/created> "2021-03-06T17:09:49.317Z"^^xsd:dateTime;
    a <http://www.w3.org/ns/ldp#BasicContainer>,<http://www.w3.org/ns/ldp#RDFSource>;
    rdfs:label "Block Object as basic container" .
