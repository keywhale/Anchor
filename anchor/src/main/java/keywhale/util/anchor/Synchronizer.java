package keywhale.util.anchor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class Synchronizer {

    private final Object defaultKey = new Object();
    private final Map<Object, Entry> locks = new HashMap<>();
    private final ThreadLocal<Map<Object, Integer>> holdCounts = ThreadLocal.withInitial(HashMap::new);

    private static class Entry {
        final ReentrantLock lock = new ReentrantLock();
        int refs = 0;
    }

    public interface Lock extends AutoCloseable {
        @Override
        void close();
    }

    public Lock lock(Object key) {
        Map<Object, Integer> holds = holdCounts.get();
        int current = holds.getOrDefault(key, 0);
        holds.put(key, current + 1);

        ReentrantLock rl;
        synchronized (locks) {
            Entry entry = locks.computeIfAbsent(key, k -> new Entry());
            if (current == 0) entry.refs++;
            rl = entry.lock;
        }
        if (current == 0) rl.lock();

        return () -> {
            int remaining = holds.get(key) - 1;
            if (remaining == 0) {
                holds.remove(key);
                rl.unlock();
                synchronized (locks) {
                    Entry entry = locks.get(key);
                    if (--entry.refs == 0) locks.remove(key);
                }
            } else {
                holds.put(key, remaining);
            }
        };
    }

    public Lock lock() {
        return this.lock(this.defaultKey);
    }

}
