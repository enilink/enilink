package net.enilink.platform.ldp.ldPatch.parse;

import net.enilink.komma.parser.sparql.tree.GraphNode;

public class FilterConstraint implements PatchConstraint{
    private Path path;
    private GraphNode value;

    public FilterConstraint(){ }

    public boolean path(Path path){
        this.path = path;
        return true;
    }

    public boolean value (GraphNode value){
        this.value = value;
        return true;
    }

    public  Path path(){ return  path;}

    public  GraphNode value(){ return  value;}
}
