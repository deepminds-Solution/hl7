package hu;

/**
 * represents terser path, value, and description
 */
public class MsgPath {
	public final String path;
	public final String value;
	// TODO remove this
	@Deprecated
	public final String description;
	public MsgPath (String path, String value) {
		this(path, value, null);
	}
	public MsgPath (String path, String value, String description) {
		this.path = path;
		this.value = value;
		this.description = description;
	}
}