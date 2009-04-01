package freenet.node;

import freenet.keys.Key;

public interface KeysFetchingLocally {
	
	/**
	 * Is this key currently being fetched locally?
	 * LOCKING: This should be safe just about anywhere, the lock protecting it is always taken last.
	 */
	public boolean hasKey(Key key);
	
	/**
	 * Is this request:token pair being executed? This applies only to
	 * non-persistent inserts, because persistent requests are selected on
	 * a request level, and requests use hasKey(). Also, activation issues
	 * with SendableRequest meaning getting a hash code would be problematic!
	 */
	public boolean hasTransientInsert(SendableInsert insert, Object token);

}
