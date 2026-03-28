package keywhale.util.loader.op;

public interface SaveOperation {
    /*
    `start` may be called from any thread, but the callback must be called from
    the thread the implementation intends subsequent state resolution to run on
    (e.g., Accessor.init for any accesses pending while the save was in progress).
    */
    public void start(Runnable callback);
}
