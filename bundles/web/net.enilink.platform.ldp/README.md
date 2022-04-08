<h1>Algemeine "constraints"</h1>

**Notes**: 
- to see examples click [here](examples.md)
- to see conformance tests report click [here](ldp-testsuite-execution-report.md)

1. root container ist immer basic containers

2. Resources angelegt durch LDPR interaction model können nicht benutzt als container und deshalb akzeptieren keine POST requests.

3. LDP-NRs werden immer durch LDPR interaction model angelegt.

4. standerdmäßig ergibt Post_req herstellung eines RDFSource sofern der type des Resources in req-header nicht bestimmt ist.

5. Post request kann  wegen "conflict" fehlschlagen (einige Statements können automatisch hinzugefügt und als " LDP-server-managed properties" berücksichtigt werden, beispielweise:
  - POST_BODY für Direct container hat keine "Relatioship statements", oder MembershipSource ist nicht vorhanden in Req-Body nicht konfugiriert  in server..

6. POST auf DirectContainer: 
in dieser Implementation werden diese "Triples" nur einmal berücksichtigt. Konfigurationen haben Priorität. Wenn keine Konfiguration dazu gibt, dann nimmt sie der Server von Req-body. wenn sie nicht vorhanden sind, schlägt er fehl

7. put request kann fehlschlagen falls es Konflikte in PUT_BODY gibt


8. standardmässig ist Content-Type für eine Anfrage ist text/turtle sofen es in requeset header nicht bestimmt


9. wenn DELETE req fehlschlägt, bleibt resource unberühbar


10. in POST request ist  null URI <> sollte verwendet werden, um auf die zu erstellende Ressource zu verweisen

11. server configuration gewinnt falls es "conflict" mit "body request" gibt. bspw. laut LDP Spec "Each LDP Direct Container representation MUST contain exactly one triple whose subject is the LDPC URI, whose predicate is the ldp:membershipResource, and whose object is the LDPC's membership-constant-URI." wenn diese swohl in Konfiguration als auch in Req-Body bestimmt sind, werden diese in Konfiguration berücksichtigt werden  

12. POST: wenn die neu zu erstellende Ressource als "Relationship Source" für einen Container konfiguriert wurde
, wird dieser direkte Container ebenfalls erstellt.
Es sollten keine zuvor erstellten direkten Container sein, deren Relationship Source" nicht vorhanden bzw. bestimmt ist. Aus diesem Grund sollen die beiden Ressourcen im selben Modell erstellt werden

13. POST: Die Ressouce kann nicht gleichzeitig von mehreren Container-types sein (entweder BASIC oder DIRECT oder INDIRECT)

14. DELETE req auf container:  alle im zu löschenden Container enthaltenen Ressourcen sollen nicht gelöscht werden, aber wenn ein Ressouce gelöscht wird, werden dessen contains Trpples (if any) gelöscht nur if the resource was bereits configured as Membership-Resource for deren Direct-Container

15. DELETE req auf root container ist nicht erlaubt, bleibt der Container erhaltet aber leer (alle contains statemnets werden gelöcht)

16. dcterms:modified and dcterms:created sind vom Server verwaltete Eigenschaften und werden ignoriert, wenn sie im Req-BODY vorhanden sind

17. PUT: schlägt fehl wenn die neue geänderte Ressource in Req-BODY nicht genau die Definitionseigenschaften (Constraints) hat wie in zu Ersetzende.
Eigenschaften als "server managed properties" berücksichtigt und automatisch hinzufügt werden sowie die sich aus Konfiguration ergebenden statements .

18. PUT: nicht zum Erstellen neuer Ressourcen verwendet werden

19. PUT: IF-Match (to avoid mid-air collisions) verwendet "weak Etag": ETags werden als gleich angesehen , wenn die Änderung seit weniger als "TIME_SLOT" erfolgt ist oder die Ressource überhaupt nicht geändert wurde (im Moment wird die Ressource als nicht geändert angesehen, wenn sich dessen "size" in Bytes  nicht geändert hat)

20. Speziale "constraints (die sich auf einzelne projekte beziehen) wie zum Beispiel deletable, modifyable.. resources sind konfigurbar und Servers, die das enilink-ldp service (extend LDPHelper wie INSITU) erweitern, geben diese Konfigurationen bei "register" weiter.
Zu diesem Zweck wurden (resource handlers) definiert, die bei implementern wie INSITU erweitert werden können und durch "register" weitergegeben. Wenn ed keine Konfigurationen gibt, werden "default" konfigurationrn berücksichtigt. In diesem Fall hat Req-Body Proiorität ..

21. Um Direct Container zu konfigurieren, sollten die "membershipRelation" und die "membershipResource" im Container-Handler bereitgestellt werden. Andernfalls versucht der Server, diese Konfiguration im "Req-Body" zu finden. Wenn dies nicht gefunden wurde und der Server raten konnte, werden die erratenen Konfigurationen verwendet (bspw. sehen Sie 12), sonst schlägt dies fehl.

22. Wenn der Handler ohne Parameter instanziiert wird, werden "parameters" auf Standardwerte gesetzt.
 Alle Parameter, die nicht mit einer geeigneten withMethod überschrieben werden, haben Standardwerte. 
Indirect containers sind noch nicht ünterstützt (nicht relevant für Insitu aber vlt für ander Projecte)

23. PATCH noch nicht ünterstützt.

24. ConstraindBy ist bis jetzt statisch
