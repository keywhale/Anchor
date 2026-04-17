package keywhale.util.anchor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class Synque {

    private final Object defaultKey = new Object();
    private final Object lock = new Object();
    private final Map<Object, ContextTracker> trackers = new HashMap<>();

    public interface Context {
        public void done();
    }

    private final class ContextTracker {
        public ContextTracker next;
        public Consumer<Context> start;
        public Context ctx;

        public void start() {
            this.start.accept(this.ctx);
        }
    }

    public void enter(Consumer<Context> start) {
        this.enter(defaultKey, start);
    }
    
    public void enter(Object key, Consumer<Context> start) {
        synchronized (this.lock) {
            AtomicBoolean done = new AtomicBoolean();
            ContextTracker trk = new ContextTracker();
            trk.start = start;
            trk.ctx = () -> {
                synchronized (this.lock) {
                    if (done.get()) return;
                    done.set(true);
                    
                    if (trk.next == null) {
                        this.trackers.remove(key);
                    } else {
                        trk.next.start();
                    }
                }
            };

            if (this.trackers.get(key) == null) {
                this.trackers.put(key, trk);
                trk.start();
            } else {
                this.trackers.get(key).next = trk;
                this.trackers.put(key, trk);
            }
        }
    }
}
