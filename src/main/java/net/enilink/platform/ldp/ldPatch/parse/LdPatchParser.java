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
    public static   LdPatch ldPatch = new LdPatch();

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
        return Sequence(index.set(""),Optional(Sequence('-', index.set("-"))), OneOrMore(Sequence(DIGIT(),index.set(index.get()+(String)match()))),push(index.get()));
    }

    public Rule PatchConstraint(){
        Var<Path> path = new Var<>();
        Var<GraphNode> value = new Var<>();
        return FirstOf(Sequence('[', WS(), Path(), path.set((Path) pop()), Optional(Sequence(WS(), '=',WS(),Value(),value.set((GraphNode) pop()),']')), push(new EqualityConstraint(path.get(), value.get()))), Sequence('!', push(new UnicityConstraint())));
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
        return Sequence(Optional(Index()), "..", Optional(Index()), push(new Slice().min(Integer.parseInt((String)pop(1))).max(Integer.parseInt((String)pop()))));
    }

//    public Rule PredicateObjectList(){
//        Var<Map<GraphNode, List<GraphNode>>> pl = new Var<>();
//        Var<GraphNode> verb = new Var<>();
//        pl.set(new HashMap<>());
//        return Sequence(Verb(), verb.set((GraphNode) pop()), ObjectList(), pl.get().put(verb.get(),(List<GraphNode>)pop()),
//                ZeroOrMore(Sequence(';', Optional(Sequence(Verb(), ObjectList(), pl.get().put((GraphNode) pop(1), (List<GraphNode>)pop()))))),
//                push(pl.get()));
//    }

 //   @SuppressWarnings("unchecked")
//    public Rule GroupGraphPattern() {
//        Var<List<Graph>> patterns = new Var<>();
//        Var<List<Expression>> filters = new Var<>();
//        return Sequence(
//                '{',
//                patterns.set(new ArrayList<Graph>()),
//                filters.set(new ArrayList<Expression>()),
//                Optional(
//                        TriplesBlock(), //
//                        patterns.get().add(
//                                new BasicGraphPattern((List<GraphNode>) pop()))),
//                ZeroOrMore(
//                        FirstOf(Sequence(GraphPatternNotTriples(), patterns
//                                        .get().add((Graph) pop())),
//                                Sequence(Filter(),
//                                        filters.get().add((Expression) pop()))),
//                        Optional('.'),
//                        Optional(
//                                TriplesBlock(), //
//                                patterns.get().add(
//                                        new BasicGraphPattern(
//                                                (List<GraphNode>) pop())))),
//                '}', //
//                push(new GraphPattern(patterns.get(), filters.get())));
//    }

  //  @SuppressWarnings("unchecked")
//    public Rule TriplesBlock() {
//        Var<List<GraphNode>> nodes = new Var<>();
//        return Sequence(
//                TriplesSameSubject(), //
//                nodes.set(new ArrayList<GraphNode>()),
//                nodes.get().add((GraphNode) pop()), //
//                Optional(
//                        '.',
//                        Optional(TriplesBlock(),
//                                nodes.get().addAll((List<GraphNode>) pop()))), //
//                push(nodes.get()));
//    }

//    public Rule GraphPatternNotTriples() {
//        return FirstOf(OptionalGraphPattern(), GroupOrUnionGraphPattern(),
//                GraphGraphPattern());
//    }

//    public Rule OptionalGraphPattern() {
//        return Sequence("Optional", GroupGraphPattern(),
//                push(new OptionalGraph((Graph) pop())));
//    }

//    public Rule GraphGraphPattern() {
//        return Sequence("GRAPH", VarOrIRIref(), GroupGraphPattern(),
//                push(new NamedGraph((GraphNode) pop(1), (Graph) pop())));
//    }

//    public Rule GroupOrUnionGraphPattern() {
//        return Sequence(
//                GroupGraphPattern(),
//                Sequence(push(LIST_BEGIN),
//                        ZeroOrMore("UNION", GroupGraphPattern()),
//                        push(new UnionGraph(popList(Graph.class, 1)))));
//    }

//    public Rule Filter() {
//        return Sequence("FILTER", Constraint());
//    }

//    public Rule Constraint() {
//        return FirstOf(BrackettedExpression(), BuiltInCall(), FunctionCall());
//    }

//    @SuppressWarnings("unchecked")
//    public Rule FunctionCall() {
//        return Sequence(IriRef(), ArgList(), //
//                push(new FunctionCall((Expression) pop(1),
//                        (List<Expression>) pop())));
//    }

//    public Rule ArgList() {
//        Var<List<Expression>> args = new Var<>();
//        return FirstOf(
//                //
//                Sequence('(', ')', push(Collections.emptyList())), //
//                Sequence(
//                        '(',
//                        args.set(new ArrayList<Expression>()),
//                        Expression(),
//                        args.get().add((Expression) pop()),
//                        ZeroOrMore(',', Expression(),
//                                args.get().add((Expression) pop())), ')', //
//                        push(args.get())) //
//        );
//    }


//    public Rule TriplesSameSubject() {
//        Var<GraphNode> subject = new Var<>();
//        return FirstOf(
//                Sequence(VarOrTerm(), subject.set((GraphNode) peek()),
//                        PropertyListNotEmpty(subject)), //
//                Sequence(TriplesNode(), subject.set((GraphNode) peek()),
//                        PropertyList(subject)) //
//        );
//    }

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

   /* public Rule PropertyList(Var<GraphNode> subject) {
        return Optional(PropertyListNotEmpty(subject));
    }*/

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
        return Sequence('(', push(LIST_BEGIN), OneOrMore(GraphNode()), //
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

//    public Rule Var() {
//        return FirstOf(VAR1(), VAR2());
//    }

    public Rule GraphTerm() {
        return FirstOf(IriRef(), RdfLiteral(), NumericLiteral(),
                BooleanLiteral(), BlankNode(),
                Sequence('(', ')', push(new IriRef(RDF_NIL))));
    }

//    public Rule Expression() {
//        return ConditionalOrExpression();
//    }

//    public Rule ConditionalOrExpression() {
//        return Sequence(
//                ConditionalAndExpression(),
//                Optional(
//                        push(LIST_BEGIN),
//                        OneOrMore("||", ConditionalAndExpression()), //
//                        push(new LogicalExpr(LogicalOperator.OR, popList(
//                                Expression.class, 1)))));
//    }

//    public Rule ConditionalAndExpression() {
//        return Sequence(
//                ValueLogical(),
//                Optional(push(LIST_BEGIN),
//                        OneOrMore("&&", ValueLogical()), //
//                        push(new LogicalExpr(LogicalOperator.AND, popList(
//                                Expression.class, 1)))));
//    }

//    public Rule ValueLogical() {
//        return RelationalExpression();
//    }

//    public Rule RelationalExpression() {
//        return Sequence(
//                NumericExpression(), //
//                Optional(RelationalOperator(), NumericExpression(), //
//                        push(new RelationalExpr((RelationalOperator) pop(1),
//                                (Expression) pop(1), (Expression) pop()))));
//    }

//    public Rule RelationalOperator() {
//        return Sequence(FirstOf('=', "!=", "<=", ">=", '<', '>'),
//                push(RelationalOperator.fromSymbol(match().trim())));
//    }

//    public Rule NumericExpression() {
//        return AdditiveExpression();
//    }

//    public Rule AdditiveExpression() {
//        Var<Expression> expr = new Var<>();
//        return Sequence(
//                MultiplicativeExpression(),
//                expr.set((Expression) pop()), //
//                ZeroOrMore(FirstOf(
//                        //
//                        Sequence('+', MultiplicativeExpression(), expr
//                                .set(new NumericExpr(NumericOperator.ADD, expr
//                                        .get(), (Expression) pop()))), //
//                        Sequence('-', MultiplicativeExpression(), expr
//                                .set(new NumericExpr(NumericOperator.SUB, expr
//                                        .get(), (Expression) pop()))), //
//                        Sequence(NumericLiteralPositive(), expr
//                                .set(new NumericExpr(NumericOperator.ADD, expr
//                                        .get(), (Expression) pop()))), //
//                        Sequence(NumericLiteralNegative(), expr
//                                .set(new NumericExpr(NumericOperator.SUB, expr
//                                        .get(), ((NumericLiteral) pop())
//                                        .negate()))))), push(expr.get()));
//    }

//    public Rule MultiplicativeExpression() {
//        Var<Expression> expr = new Var<>();
//        return Sequence(UnaryExpression(),
//                expr.set((Expression) pop()), //
//                ZeroOrMore(FirstOf(
//                        //
//                        Sequence('*', UnaryExpression(), expr
//                                .set(new NumericExpr(NumericOperator.MUL, expr
//                                        .get(), (Expression) pop()))), //
//                        Sequence('/', UnaryExpression(), expr
//                                .set(new NumericExpr(NumericOperator.DIV, expr
//                                        .get(), (Expression) pop()))) //
//                )), push(expr.get()));
//    }

//    public Rule UnaryExpression() {
//        return FirstOf(
//                Sequence('!',
//                        PrimaryExpression(), //
//                        push(new LogicalExpr(LogicalOperator.NOT, Collections
//                                .singletonList((Expression) pop())))), //
//                Sequence('+', PrimaryExpression()), //
//                Sequence('-', PrimaryExpression(), //
//                        push(new NegateExpr((Expression) pop()))), //
//                PrimaryExpression());
//    }

//    public Rule PrimaryExpression() {
//        return FirstOf(BrackettedExpression(), BuiltInCall(),
//                IriRefOrFunction(), RdfLiteral(), NumericLiteral(),
//                BooleanLiteral(), VAR1());
//    }

//    public Rule BrackettedExpression() {
//        return Sequence('(', Expression(), ')');
//    }

//    public boolean beginExprList() {
//        push(match());
//        push(LIST_BEGIN);
//        return true;
//    }

//    public Rule BuiltInCall() {
//        Var<List<Expression>> args = new Var<>();
//        return Sequence(
//                FirstOf(Sequence("STR", beginExprList(), '(', Expression(), ')'), //
//                        Sequence("LANG", beginExprList(), '(', Expression(),
//                                ')'), //
//                        Sequence("LANGMATCHES", beginExprList(), '(',
//                                Expression(), ',', Expression(), ')'), //
//                        Sequence("DATATYPE", beginExprList(), '(',
//                                Expression(), ')'), //
//                        Sequence("BOUND", beginExprList(), '(', VAR1(), ')'), //
//                        Sequence("SAMETERM", beginExprList(), '(',
//                                Expression(), ',', Expression(), ')'), //
//                        Sequence("ISIRI", beginExprList(), '(', Expression(),
//                                ')'), //
//                        Sequence("ISURI", beginExprList(), '(', Expression(),
//                                ')'), //
//                        Sequence("ISBLANK", beginExprList(), '(', Expression(),
//                                ')'), //
//                        Sequence("ISLITERAL", beginExprList(), '(',
//                                Expression(), ')'), //
//                        Sequence("REGEX", beginExprList(), '(', Expression(),
//                                ',', Expression(), Optional(',', Expression()),
//                                ')') //
//                ), //
//                args.set(popList(Expression.class)), //
//                push(new BuiltInCall((String) pop(), args.get())) //
//        );
//    }

//    @SuppressWarnings("unchecked")
//    public Rule IriRefOrFunction() {
//        return Sequence(
//                IriRef(), //
//                Optional(ArgList(), push(new FunctionCall((Expression) pop(1),
//                        (List<Expression>) pop()))));
//    }

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
