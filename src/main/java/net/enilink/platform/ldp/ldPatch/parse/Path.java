package net.enilink.platform.ldp.ldPatch.parse;

import java.util.ArrayList;
import java.util.List;

public class Path {
    private List<PathElement> elements = new ArrayList<>();

    public Path(List<PathElement> elements){ this.elements = elements;}

    public List<PathElement> getElements() {
        return elements;
    }
}
