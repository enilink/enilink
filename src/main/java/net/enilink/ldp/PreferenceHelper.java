package net.enilink.ldp;

public class PreferenceHelper {

	public static final int MINIMAL_CONTAINER = 1;
	public static final int INCLUDE_CONTAINMENT = 2;
	public static final int INCLUDE_MEMBERSHIP = 4;

	public static int defaultPreferences() {
		return INCLUDE_CONTAINMENT | INCLUDE_MEMBERSHIP;
	}
}
