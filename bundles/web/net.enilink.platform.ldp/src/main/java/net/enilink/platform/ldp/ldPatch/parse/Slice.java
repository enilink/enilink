package net.enilink.platform.ldp.ldPatch.parse;

public class Slice {
	private int min = Integer.MIN_VALUE;
	private int max = Integer.MAX_VALUE;

	public boolean min(int min) {
		this.min = min;
		return true;
	}

	public int min() {
		return this.min;
	}

	public boolean max(int max) {
		this.max = max;
		return true;
	}

	public int max() {
		return this.max;
	}
}
