package net.enilink.platform.ldp.ldPatch.parse;

import net.enilink.komma.parser.sparql.tree.Variable;

public class Cut implements Operation{
    private Variable variable;

    public Variable variable(){ return variable;}

    public Cut variable(Variable v){
        this.variable = v;
        return  this;
    }
}
