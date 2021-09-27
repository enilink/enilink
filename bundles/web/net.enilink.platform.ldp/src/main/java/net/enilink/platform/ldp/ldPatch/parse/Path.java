package net.enilink.platform.ldp.ldPatch.parse;

import java.util.ArrayList;
import java.util.List;

public class Path {
	private List<PathElement> elements;
	private int step = 0;

	public Path(List<PathElement> elements) {
		this.elements = elements;
		if (null != elements && elements.get(0) instanceof Step)
			this.step = ((Step) elements.get(0)).step();
	}

	public List<PathElement> getElements() {
		return elements;
	}

	public int step() {
		return this.step;
	}
}
