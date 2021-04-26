package net.enilink.platform.ldp.ldPatch.parse;

import net.enilink.komma.parser.sparql.tree.GraphNode;

public class EqualityConstraint implements PatchConstraint{
    private Path path;
    private GraphNode value;

    public EqualityConstraint(Path path, GraphNode value){
        this.path = path;
        this.value = value;
    }

    public  Path path(){ return  path;}

    public  GraphNode value(){ return  value;}
}
