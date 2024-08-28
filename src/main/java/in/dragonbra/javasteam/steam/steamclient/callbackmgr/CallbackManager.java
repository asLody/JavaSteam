package in.dragonbra.javasteam.steam.steamclient.callbackmgr;

import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.types.JobID;
import in.dragonbra.javasteam.util.compat.Consumer;

import java.io.Closeable;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class is a utility for routing callbacks to function calls.
 * In order to bind callbacks to functions, an instance of this class must be created for the
 * {@link in.dragonbra.javasteam.steam.steamclient.SteamClient SteamClient} instance that will be posting callbacks.
 */
public class CallbackManager implements ICallbackMgrInternals {

    private final SteamClient steamClient;

    private final Set<CallbackBase> registeredCallbacks = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Initializes a new instance of the {@link CallbackManager} class.
     *
     * @param steamClient The {@link SteamClient SteamClient} instance to handle the callbacks of.
     */
    public CallbackManager(SteamClient steamClient) {
        if (steamClient == null) {
            throw new IllegalArgumentException("steamclient is null");
        }
        this.steamClient = steamClient;
    }

    /**
     * Runs a single queued callback.
     * If no callback is queued, this method will instantly return.
     *
     * @return true if a callback has been run, false otherwise.
     */
    public boolean runCallbacks() {
        var call = steamClient.getCallback();

        if (call == null) {
            return false;
        }

        handle(call);
        return true;
    }

    /**
     * Blocks the current thread to run a single queued callback.
     * If no callback is queued, the method will block for the given timeout or until a callback becomes available.
     *
     * @param timeout The length of time to block.
     * @return true if a callback has been run, false otherwise.
     */
    public boolean runWaitCallbacks(long timeout) {
        var call = steamClient.waitForCallback(timeout);

        if (call == null) {
            return false;
        }

        handle(call);
        return true;
    }

    /**
     * Blocks the current thread to run all queued callbacks.
     * If no callback is queued, the method will block for the given timeout or until a callback becomes available.
     * This method returns once the queue has been emptied.
     *
     * @param timeout The length of time to block.
     */
    public void runWaitAllCallbacks(long timeout) {
        if(!runWaitCallbacks(timeout)) {
            return;
        }

        //noinspection StatementWithEmptyBody
        while (runCallbacks());
    }

    /**
     * Blocks the current thread to run a single queued callback.
     * If no callback is queued, the method will block until one becomes available.
     */
    public void runWaitCallbacks() {
        var call = steamClient.getCallback();
        handle(call);
    }

    // runWaitCallbackAsync()

    /**
     * Registers the provided {@link Consumer} to receive callbacks of type {@link TCallback}
     *
     * @param callbackType type of the callback
     * @param jobID        The {@link JobID}  of the callbacks that should be subscribed to.
     * @param callbackFunc The function to invoke with the callback.
     * @param <TCallback>  The type of callback to subscribe to.
     * @return An {@link Closeable}. Disposing of the return value will unsubscribe the callbackFunc .
     */
    public <TCallback extends ICallbackMsg> Closeable subscribe(Class<? extends TCallback> callbackType, JobID jobID, Consumer<TCallback> callbackFunc) {
        if (jobID == null) {
            throw new IllegalArgumentException("jobID is null");
        }

        if (callbackFunc == null) {
            throw new IllegalArgumentException("callbackFunc is null");
        }

        Callback<TCallback> callback = new Callback<>(callbackType, callbackFunc, this, jobID);
        return new Subscription(this, callback);
    }

    /**
     * REgisters the provided {@link Consumer} to receive callbacks of type {@link TCallback}
     *
     * @param callbackType type of the callback
     * @param callbackFunc The function to invoke with the callback.
     * @param <TCallback>  The type of callback to subscribe to.
     * @return An {@link Closeable}. Disposing of the return value will unsubscribe the callbackFunc .
     */
    public <TCallback extends ICallbackMsg> Closeable subscribe(Class<? extends TCallback> callbackType, Consumer<TCallback> callbackFunc) {
        return subscribe(callbackType, JobID.INVALID, callbackFunc);
    }

    @Override
    public void register(CallbackBase callback) {
        registeredCallbacks.add(callback);
    }

    @Override
    public void unregister(CallbackBase callback) {
        registeredCallbacks.remove(callback);
    }

    private void handle(ICallbackMsg call) {
        for (CallbackBase callback : registeredCallbacks) {
            if (callback.getCallbackType().isAssignableFrom(call.getClass())) {
                callback.run(call);
            }
        }
    }

}
