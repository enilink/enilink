package net.enilink.platform.ldp.ldPatch.parse;

import net.enilink.komma.parser.BaseRdfParser;
import net.enilink.komma.parser.sparql.tree.*;
import org.parboiled.Rule;
import org.parboiled.support.Var;

import java.util.ArrayList;
import java.util.List;

public class LdPatchParser extends BaseRdfParser {
    public static String RDF_NAMESPACE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    public static String RDF_TYPE = RDF_NAMESPACE + "type";
    public static String RDF_NIL = RDF_NAMESPACE + "nil";
    public  LdPatch ldPatch = new LdPatch();

    public Rule LdPatch() {
        return Sequence(
                Prologue(), WS(),
                Statements(),EOI, push(ldPatch));
    }

    public Rule Prologue() {
        Var<List<PrefixDecl>> prefixes = new Var<>();
        return Sequence(
                prefixes.set(new ArrayList<>()),
                ZeroOrMore(PrefixDecl(), prefixes.get().add((PrefixDecl) pop())), //
                ldPatch.setPrologue(new Prologue(null, prefixes.get())));
    }

    public Rule PrefixDecl() {
        return Sequence("@prefix", PNAME_NS(), WS(), IRI_REF(), WS(), '.',//
                push(new PrefixDecl((String) pop(1), (IriRef) pop())));
    }

    public Rule Statements(){
        Var<List<Operation>> ops = new Var<>();
        return Sequence(
                ops.set(new ArrayList()),
                ZeroOrMore(Statement(), ops.get().add((Operation) pop())),
                ldPatch.operations(ops.get()));
    }

    public Rule Statement(){
      return FirstOf(Bind(), Add(), AddNew(), Delete(), DeleteExisting(), Cut(), UpdateList());
    }

    public Rule Bind(){
        Var<Variable> varName = new Var<>();
        Var<GraphNode> varValue = new Var<>();
        Var<Path> varPath = new Var<>();
        Var<Bind> bind = new Var<>();
        return Sequence(FirstOf("Bind", "B"), WS(), VAR1(), varName.set((Variable) pop()), WS(), //
                 Value(), varValue.set((GraphNode)pop()), Optional(Sequence(Path(), varPath.set((Path)pop()))), //
                bind.set(new Bind(varName.get().getName(), varValue.get(), varPath.get())), //
                ldPatch.addVariable(varName.get().getName(), bind.get()), push(bind.get()),'.');

    }

    public Rule Add(){
      return Sequence(FirstOf("Add", "A"),'{', Graph(),'}', push(new Add().graph(((List<GraphNode>)pop()))),'.');
    }

    public Rule AddNew(){
        return Sequence(FirstOf("AddNew", "AN"),'{', Graph(),'}', push(new AddNew().graph(((List<GraphNode>)pop()))),'.');
    }

    public Rule Delete(){
        return Sequence(FirstOf("Delete", "D"),'{', Graph(),'}', push(new Delete().graph(((List<GraphNode>)pop()))),'.');
    }

    public Rule DeleteExisting(){
        return Sequence(FirstOf("DeleteExisting", "DE"),'{', Graph(),'}', push(new DeleteExisting().graph((((List<GraphNode>)pop())))),'.');
    }

    public Rule Cut(){
        return Sequence(FirstOf("Cut", "C"), VAR1(),push(new Cut().variable((Variable)pop())), '.');
    }

    public Rule UpdateList(){
        return Sequence(FirstOf("UpdateList", "UL"), VarOrIRIref(), IriRef(),Slice(), Collection(),
                push(new UpdateList().subject((AbstractGraphNode)pop(3)).predicate((AbstractGraphNode)pop(2)).slice((Slice) pop(1)).collection((Collection)pop())),'.');
    }

    public Rule Value(){
        return FirstOf(IriRef(), RdfLiteral(), NumericLiteral(), BooleanLiteral(),VAR1());
    }

    public Rule Path(){
        Var<List<PathElement>> path = new Var<>();
      return Sequence( path.set(new ArrayList<>()),
              ZeroOrMore(FirstOf(Sequence(WS(), '/',WS(), Step(), path.get().add((Step)pop())), Sequence(PatchConstraint(), path.get().add((PatchConstraint)pop())))),
              push(new Path(path.get())));
    }

    public Rule Step(){
        Var<Integer> step= new Var<>();
        Var<AbstractGraphNode> iri = new Var<>();
        return Sequence(FirstOf(Sequence(IriRef(),iri.set((AbstractGraphNode) pop()),step.set(1)), Sequence('^', step.set(-1), IriRef(), iri.set((AbstractGraphNode) pop())), //
                 Sequence(Index(),step.set(Integer.parseInt((String) pop())))), push(new Step(step.get(), iri.get())));
    }

    public Rule Index(){
        Var<String> index = new Var<>();
        return Sequence(index.set(""),Optional(Sequence('-', index.set("-"))), OneOrMore(Sequence(DIGIT(),index.set(index.get()+match()))),push(index.get()));
    }

    public Rule PatchConstraint(){
        Var<FilterConstraint> cons= new Var<>();
        return FirstOf(Sequence('[', WS(), cons.set(new FilterConstraint()),Path(), cons.get().path((Path) pop()),
                Optional(Sequence(WS(), '=',WS(),Value(),cons.get().value((GraphNode) pop()))),']',
                push(cons.get())), Sequence('!', push(new UnicityConstraint())));
    }

    public Rule Graph(){
        Var<List<GraphNode>> subjects = new Var<>();
        return Sequence(subjects.set(new ArrayList<>()) ,Triples(),subjects.get().add((GraphNode) pop()), ZeroOrMore(Sequence('.', Triples(), subjects.get().add((GraphNode) pop()))),push(subjects.get()), Optional('.'));
    }

    public Rule Triples(){
        Var<GraphNode> subj = new Var<>();
        return Sequence(Subject(), subj.set((GraphNode) pop()), FirstOf(PropertyListNotEmpty(subj), BlankNodePropertyList()), Optional(PropertyListNotEmpty(subj)), push(subj.get()));
    }

    public Rule Subject(){
        return FirstOf(IriRef(), BlankNode(), Collection(), VAR1());
    }

    public Rule Slice(){
        Var<Slice> slice  = new Var<>();
        return Sequence(slice.set(new Slice()),Optional(Sequence(Index(),slice.get().min(Integer.parseInt((String)pop())))), "..",
                Optional(Sequence(Index(), slice.get().max(Integer.parseInt((String)pop())))),push(slice.get()));
    }

    public boolean addPropertyPatterns(PropertyList propertyList,
                                       GraphNode predicate, List<GraphNode> objects) {
        for (GraphNode object : objects) {
            propertyList.add(new PropertyPattern(predicate, object));
        }
        return true;
    }

    public PropertyList createPropertyList(GraphNode subject) {
        PropertyList propertyList = subject.getPropertyList();
        if ((propertyList == null || PropertyList.EMPTY_LIST
                .equals(propertyList)) && subject instanceof AbstractGraphNode) {
            propertyList = new PropertyList();
            ((AbstractGraphNode) subject).setPropertyList(propertyList);
        }
        return propertyList;
    }

    @SuppressWarnings("unchecked")
    public Rule PropertyListNotEmpty(Var<GraphNode> subject) {
        Var<PropertyList> propertyList = new Var<>();
        return Sequence(
                Verb(),
                ObjectList(), //
                propertyList.set(createPropertyList(subject.get())),
                addPropertyPatterns(propertyList.get(), (GraphNode) pop(1),
                        (List<GraphNode>) pop()), //
                ZeroOrMore(
                        ';',
                        Optional(Verb(),
                                ObjectList(), //
                                addPropertyPatterns(propertyList.get(),
                                        (GraphNode) pop(1),
                                        (List<GraphNode>) pop()))) //
        );
    }

    public Rule ObjectList() {
        return Sequence(
                Object(),
                Sequence(push(LIST_BEGIN), ZeroOrMore(',', Object()),
                        push(popList(GraphNode.class, 1))));
    }

    public Rule Object() {
        return GraphNode();
    }

    public Rule Verb() {
        return FirstOf(IriRef(), Sequence('a', push(new IriRef(RDF_TYPE))));
    }

    public Rule TriplesNode() {
        return FirstOf(Collection(), BlankNodePropertyList());
    }

    public Rule BlankNodePropertyList() {
        Var<GraphNode> subject = new Var<>();
        return Sequence('[', push(new BNodePropertyList()),
                subject.set((GraphNode) peek()), PropertyListNotEmpty(subject),
                ']');
    }

    public Rule Collection() {
        return Sequence('(', push(LIST_BEGIN), ZeroOrMore(GraphNode()), //
                push(new Collection(popList(GraphNode.class))), ')');
    }

    public Rule GraphNode() {
        return FirstOf(VarOrTerm(), TriplesNode());
    }

    public Rule VarOrTerm() {
        return FirstOf(VAR1(), GraphTerm());
    }

    public Rule VarOrIRIref() {
        return FirstOf(VAR1(), IriRef());
    }

    public Rule GraphTerm() {
        return FirstOf(IriRef(), RdfLiteral(), NumericLiteral(),
                BooleanLiteral(), BlankNode(),
                Sequence('(', ')', push(new IriRef(RDF_NIL))));
    }

    public Rule VAR1() {
        return Sequence(Ch('?'), VARNAME());
    }

    public Rule VARNAME() {
        return Sequence(
                Sequence(
                        FirstOf(PN_CHARS_U(), DIGIT()),
                        ZeroOrMore(FirstOf(PN_CHARS_U(), DIGIT(), Ch('\u00B7'),
                                CharRange('\u0300', '\u036F'),
                                CharRange('\u203F', '\u2040')))),
                push(new Variable(match())), WS());
    }

    @Override
    protected Rule fromStringLiteral(String string) {
        return Sequence(IgnoreCase(string), WS());
    }
}
