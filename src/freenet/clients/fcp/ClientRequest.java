package freenet.clients.fcp;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;

import freenet.client.async.ClientContext;
import freenet.client.async.ClientRequester;
import freenet.client.async.PersistenceDisabledException;
import freenet.client.async.PersistentJob;
import freenet.clients.fcp.RequestIdentifier.RequestType;
import freenet.crypt.ChecksumChecker;
import freenet.keys.FreenetURI;
import freenet.node.PrioRunnable;
import freenet.node.RequestClient;
import freenet.node.RequestStarter;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;
import freenet.support.io.ResumeFailedException;
import freenet.support.io.StorageFormatException;

/**
 * A request process carried out by the node for an FCP client.
 * Examples: ClientGet, ClientPut, MultiGet.
 */
public abstract class ClientRequest implements Serializable {

    private static final long serialVersionUID = 1L;
    /** URI to fetch, or target URI to insert to */
	protected FreenetURI uri;
	/** Unique request identifier */
	protected final String identifier;
	/** Verbosity level. Relevant to all ClientRequests, although they interpret it
	 * differently. */
	protected final int verbosity;
	/** Original FCPConnectionHandler. Null if persistence != connection */
	protected transient final FCPConnectionHandler origHandler;
	/** Is the request on the global queue? */
    protected final boolean global;
    /** If the request isn't on the global queue, what is the client's name? */
    protected final String clientName;
	/** Client */
	protected transient FCPClient client;
	/** Priority class */
	protected short priorityClass;
	/** Is the request scheduled as "real-time" (as opposed to bulk)? */
	protected final boolean realTime;
	/** Persistence type */
	protected final short persistenceType;
	/** Has the request finished? */
	protected boolean finished;
	/** Client token (string to feed back to the client on a Persistent* when he does a
	 * ListPersistentRequests). */
	protected String clientToken;
	/** Timestamp : startup time */
	protected final long startupTime;
	/** Timestamp : completion time */
	protected long completionTime;

	/** Timestamp: last activity. */
	protected long lastActivity;

	protected transient RequestClient lowLevelClient;
	private final int hashCode; // for debugging it is good to have a persistent id
	
	@Override
	public int hashCode() {
		return hashCode;
	}

	private static volatile boolean logMINOR;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	public ClientRequest(FreenetURI uri2, String identifier2, int verbosity2, String charset, 
			FCPConnectionHandler handler, FCPClient client, short priorityClass2, short persistenceType2, boolean realTime, String clientToken2, boolean global) {
		int hash = super.hashCode();
		if(hash == 0) hash = 1;
		hashCode = hash;
		this.uri = uri2;
		this.identifier = identifier2;
		if(global) {
			this.verbosity = Integer.MAX_VALUE;
			this.clientName = null;
		} else {
			this.verbosity = verbosity2;
			this.clientName = client.name;
	    }
		this.finished = false;
		this.priorityClass = priorityClass2;
		this.persistenceType = persistenceType2;
		this.clientToken = clientToken2;
		this.global = global;
		if(persistenceType == PERSIST_CONNECTION) {
			this.origHandler = handler;
			lowLevelClient = origHandler.connectionRequestClient(realTime);
			this.client = null;
		} else {
			origHandler = null;
			this.client = client;
			assert client != null;
			assert(client.persistenceType == persistenceType);
			lowLevelClient = client.lowLevelClient(realTime);
		}
		assert lowLevelClient != null;
		this.startupTime = System.currentTimeMillis();
		this.realTime = realTime;
	}

	public ClientRequest(FreenetURI uri2, String identifier2, int verbosity2, String charset, 
			FCPConnectionHandler handler, short priorityClass2, short persistenceType2, final boolean realTime, String clientToken2, boolean global) {
		int hash = super.hashCode();
		if(hash == 0) hash = 1;
		hashCode = hash;
		this.uri = uri2;
		
		this.identifier = identifier2;
		this.finished = false;
		this.priorityClass = priorityClass2;
		this.persistenceType = persistenceType2;
		this.clientToken = clientToken2;
		this.global = global;
		if(persistenceType == PERSIST_CONNECTION) {
			this.origHandler = handler;
			client = null;
			lowLevelClient = new RequestClient() {

				@Override
				public boolean persistent() {
					return false;
				}

				@Override
				public boolean realTimeFlag() {
					return realTime;
				}
				
			};
			this.clientName = null;
            this.verbosity = verbosity2;
		} else {
			origHandler = null;
			if(global) {
				client = persistenceType == PERSIST_FOREVER ? handler.server.globalForeverClient : handler.server.globalRebootClient;
	            this.verbosity = Integer.MAX_VALUE;
	            clientName = null;
			} else {
				client = persistenceType == PERSIST_FOREVER ? handler.getForeverClient() : handler.getRebootClient();
	            this.verbosity = verbosity2;
	            this.clientName = client.name;
			}
			lowLevelClient = client.lowLevelClient(realTime);
			if(lowLevelClient == null)
				throw new NullPointerException("No lowLevelClient from client: "+client+" global = "+global+" persistence = "+persistenceType);
		}
		if(lowLevelClient.persistent() != (persistenceType == PERSIST_FOREVER))
			throw new IllegalStateException("Low level client.persistent="+lowLevelClient.persistent()+" but persistence type = "+persistenceType);
		if(client != null)
			assert(client.persistenceType == persistenceType);
		this.startupTime = System.currentTimeMillis();
		this.realTime = realTime;
	}
	
	protected ClientRequest() {
	    // For serialization.
	    identifier = null;
	    verbosity = 0;
	    origHandler = null;
	    global = false;
	    clientName = null;
	    realTime = false;
	    persistenceType = 0;
	    startupTime = 0;
	    hashCode = 0;
	}

    /** Lost connection */
	public abstract void onLostConnection(ClientContext context);

	/** Send any pending messages for a persistent request e.g. after reconnecting */
	public abstract void sendPendingMessages(FCPConnectionOutputHandler handler, boolean includePersistentRequest, boolean includeData, boolean onlyData);

	// Persistence

	public static final short PERSIST_CONNECTION = 0;
	public static final short PERSIST_REBOOT = 1;
	public static final short PERSIST_FOREVER = 2;

	public static String persistenceTypeString(short type) {
		switch(type) {
		case PERSIST_CONNECTION:
			return "connection";
		case PERSIST_REBOOT:
			return "reboot";
		case PERSIST_FOREVER:
			return "forever";
		default:
			return Short.toString(type);
		}
	}

	public static short parsePersistence(String string) {
		if((string == null) || string.equalsIgnoreCase("connection"))
			return PERSIST_CONNECTION;
		if(string.equalsIgnoreCase("reboot"))
			return PERSIST_REBOOT;
		if(string.equalsIgnoreCase("forever"))
			return PERSIST_FOREVER;
		return Short.parseShort(string);
	}

	abstract void register(boolean noTags) throws IdentifierCollisionException;

	public void cancel(ClientContext context) {
		ClientRequester cr = getClientRequest();
		// It might have been finished on startup.
		if(logMINOR) Logger.minor(this, "Cancelling "+cr+" for "+this+" persistenceType = "+persistenceType);
		if(cr != null) cr.cancel(context);
		freeData();
	}

	public boolean isPersistentForever() {
		return persistenceType == ClientRequest.PERSIST_FOREVER;
	}

	/** Is the request persistent? False = we can drop the request if we lose the connection */
	public boolean isPersistent() {
		return persistenceType != ClientRequest.PERSIST_CONNECTION;
	}

	public boolean hasFinished() {
		return finished;
	}

	/** Get identifier string for request */
	public String getIdentifier() {
		return identifier;
	}

	protected abstract ClientRequester getClientRequest();

	/** Completed request dropped off the end without being acknowledged */
	public void dropped(ClientContext context) {
		cancel(context);
		freeData();
	}

	/** Return the priority class */
	public short getPriority(){
		return priorityClass;
	}

	/** Free cached data bucket(s) */
	protected abstract void freeData(); 

	/** Request completed. But we may have to stick around until we are acked. */
	protected void finish() {
		if(persistenceType == ClientRequest.PERSIST_CONNECTION)
			origHandler.finishedClientRequest(this);
		else
			client.finishedClientRequest(this);
	}

	public abstract double getSuccessFraction();

	public abstract double getTotalBlocks();
	public abstract double getMinBlocks();
	public abstract double getFetchedBlocks();
	public abstract double getFailedBlocks();
	public abstract double getFatalyFailedBlocks();

	public abstract String getFailureReason(boolean longDescription);

	/**
	 * Has the total number of blocks to insert been determined yet?
	 */
	public abstract boolean isTotalFinalized();

	public void onMajorProgress() {
		// Ignore
	}

	/** Start the request, if it has not already been started. */
	public abstract void start(ClientContext context);

	protected boolean started;

	public boolean isStarted() {
		return started;
	}

	public abstract boolean hasSucceeded();

	/**
	 * Returns the time of the request’s last activity, or {@code 0} if there is
	 * no known last activity.
	 *
	 * @return The time of the request’s last activity, or {@code 0}
	 */
	public long getLastActivity() {
		return lastActivity;
	}

	public abstract boolean canRestart();

	public abstract boolean restart(ClientContext context, boolean disableFilterData) throws PersistenceDisabledException;

	protected abstract FCPMessage persistentTagMessage();

	/**
	 * Called after a ModifyPersistentRequest.
	 * Sends a PersistentRequestModified message to clients if any value changed. 
	 */
	public void modifyRequest(String newClientToken, short newPriorityClass, FCPServer server) {

		boolean clientTokenChanged = false;
		boolean priorityClassChanged = false;

		if(newClientToken != null) {
			if( clientToken != null ) {
				if( !newClientToken.equals(clientToken) ) {
					this.clientToken = newClientToken; // token changed
					clientTokenChanged = true;
				}
			} else {
				this.clientToken = newClientToken; // first time the token is set
				clientTokenChanged = true;
			}
		}
		
		if(newPriorityClass >= 0 && newPriorityClass != priorityClass) {
			this.priorityClass = newPriorityClass;
			ClientRequester r = getClientRequest();
			r.setPriorityClass(priorityClass, server.core.clientContext);
			priorityClassChanged = true;
			if(client != null) {
				RequestStatusCache cache = client.getRequestStatusCache();
				if(cache != null) {
					cache.setPriority(identifier, newPriorityClass);
				}
			}
		}

		if(! ( clientTokenChanged || priorityClassChanged ) ) {
			return; // quick return, nothing was changed
		}
		
		server.core.clientContext.jobRunner.setCheckpointASAP();
		
		// this could become too complex with more parameters, but for now its ok
		final PersistentRequestModifiedMessage modifiedMsg;
		if( clientTokenChanged && priorityClassChanged ) {
			modifiedMsg = new PersistentRequestModifiedMessage(identifier, global, priorityClass, clientToken);
		} else if( priorityClassChanged ) {
			modifiedMsg = new PersistentRequestModifiedMessage(identifier, global, priorityClass);
		} else if( clientTokenChanged ) {
			modifiedMsg = new PersistentRequestModifiedMessage(identifier, global, clientToken);
		} else {
			return; // paranoia, we should not be here if nothing was changed!
		}
		client.queueClientRequestMessage(modifiedMsg, 0);
	}

	public void restartAsync(final FCPServer server, final boolean disableFilterData) throws PersistenceDisabledException {
		synchronized(this) {
			this.started = false;
		}
		if(client != null) {
			RequestStatusCache cache = client.getRequestStatusCache();
			if(cache != null) {
				cache.updateStarted(identifier, false);
			}
		}
		if(persistenceType == PERSIST_FOREVER) {
		server.core.clientContext.jobRunner.queue(new PersistentJob() {

			@Override
			public boolean run(ClientContext context) {
			    try {
			        restart(context, disableFilterData);
			    } catch (PersistenceDisabledException e) {
			        // Impossible
			    }
				return true;
			}
			
		}, NativeThread.HIGH_PRIORITY);
		} else {
			server.core.getExecutor().execute(new PrioRunnable() {

				@Override
				public int getPriority() {
					return NativeThread.NORM_PRIORITY;
				}

				@Override
				public void run() {
				    try {
                        restart(server.core.clientContext, disableFilterData);
                    } catch (PersistenceDisabledException e) {
                        // Impossible
                    }
				}
				
			}, "Restart request");
		}
	}

	/**
	 * Called after a RemovePersistentRequest. Send a PersistentRequestRemoved to the clients.
	 * If the request is in the database, delete it.
	 */
	public void requestWasRemoved(ClientContext context) {
		if(persistenceType != PERSIST_FOREVER) return;
	}

	protected boolean isGlobalQueue() {
		if(client == null) return false;
		return client.isGlobalQueue;
	}

	public FCPClient getClient(){
		return client;
	}

	abstract RequestStatus getStatus();
	
	private static final long CLIENT_DETAIL_MAGIC = 0xebf0b4f4fa9f6721L;
	private static final int CLIENT_DETAIL_VERSION = 1;

    public void getClientDetail(DataOutputStream dos, ChecksumChecker checker) throws IOException {
        if(persistenceType != PERSIST_FOREVER) return;
        dos.writeLong(CLIENT_DETAIL_MAGIC);
        dos.writeInt(CLIENT_DETAIL_VERSION);
        // Identify the request first.
        RequestIdentifier req = getRequestIdentifier();
        req.writeTo(dos);
        // Basic details needed for scheduling, reporting and completion.
        dos.writeBoolean(realTime);
        dos.writeInt(verbosity);
        dos.writeLong(startupTime);
        // persistenceType is assumed to be PERSIST_FOREVER.
        // uri will be handled by subclasses.
        // This can change.
        dos.writeShort(priorityClass);
        // This can change and is variable size.
        if(clientToken == null)
            dos.writeBoolean(false);
        else {
            dos.writeBoolean(true);
            dos.writeUTF(clientToken);
        }
        // Stuff that changes on completion
        dos.writeBoolean(finished);
    }
    
    protected ClientRequest(DataInputStream dis, RequestIdentifier reqID, 
            ClientContext context) throws IOException, StorageFormatException {
        long magic = dis.readLong();
        if(magic != CLIENT_DETAIL_MAGIC)
            throw new StorageFormatException("Bad magic");
        int version = dis.readInt();
        if(version != CLIENT_DETAIL_VERSION)
            throw new StorageFormatException("Bad version");
        RequestIdentifier copyReq = new RequestIdentifier(dis);
        if(!copyReq.equals(reqID))
            throw new StorageFormatException("Request identifier has changed");
        realTime = dis.readBoolean();
        verbosity = dis.readInt();
        startupTime = dis.readLong();
        priorityClass = dis.readShort();
        if(priorityClass < RequestStarter.MAXIMUM_PRIORITY_CLASS || 
                priorityClass > RequestStarter.MINIMUM_PRIORITY_CLASS)
            throw new StorageFormatException("Bogus priority");
        if(dis.readBoolean())
            clientToken = dis.readUTF();
        else
            clientToken = null;
        finished = dis.readBoolean();
        persistenceType = PERSIST_FOREVER;
        origHandler = null;
        identifier = reqID.identifier;
        global = reqID.globalQueue;
        clientName = reqID.clientName;
        hashCode = super.hashCode();
        // We can't wait until onResume() to get the client, because it may be used in the 
        // constructors.
        this.client = context.persistentRoot.makeClient(global, clientName);
        this.lowLevelClient = client.lowLevelClient(realTime);
    }

    /** Called just after serializing in the request. Called by the ClientRequester, i.e. the tree 
     * starts there, and we MUST NOT call back to it or we get an infinite recursion. The main 
     * purpose of this method is to give us an opportunity to connect to the various (transient) 
     * system utilities we get from ClientContext, e.g. bucket factories, the FCP persistent root 
     * etc. The base class implementation in ClientRequest will register the request with an 
     * FCPClient via the new FCPPersistentRoot.
     * @param context Contains all the important system utilities.
     * @throws ResumeFailedException 
     */
    public final void onResume(ClientContext context) throws ResumeFailedException {
        client = context.persistentRoot.makeClient(global, clientName);
        lowLevelClient = client.lowLevelClient(realTime);
        innerResume(context);
        ClientRequester req = getClientRequest();
        if(req != null) req.onResume(context); // Can legally be null.
        context.persistentRoot.resume(this, global, clientName);
    }
    
    protected abstract void innerResume(ClientContext context) throws ResumeFailedException;

    public RequestClient getRequestClient() {
        return lowLevelClient;
    }

    /** Get the RequestIdentifier. This just includes the queue and the identifier. */
    public RequestIdentifier getRequestIdentifier() {
        if(persistenceType == PERSIST_CONNECTION) throw new IllegalStateException(); // Not associated with any client.
        return new RequestIdentifier(global, clientName, identifier, getType());
    }
    
    abstract RequestIdentifier.RequestType getType();

    public static ClientRequest restartFrom(DataInputStream dis, RequestIdentifier reqID,
            ClientContext context, ChecksumChecker checker) throws StorageFormatException, IOException {
        switch(reqID.type) {
        case GET:
            return ClientGet.restartFrom(dis, reqID, context, checker);
        default:
            return null;
        }
    }

    /** Return true if we resumed the original fetch from stored data (usually a file for a 
     * splitfile download), rather than having to restart it (which happens in most other cases
     * when we resume). */
    public abstract boolean fullyResumed();

    /** Called just before the final write when the node is shutting down. Should write any dirty
     * data to disk etc. */
    public void onShutdown(ClientContext context) {
        ClientRequester request = getClientRequest();
        if(request != null)
            request.onShutdown(context);
    }
}