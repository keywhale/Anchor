package keywhale.bukkit.util.loader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import keywhale.bukkit.util.loader.op.AccessOperation;
import keywhale.bukkit.util.loader.op.SaveOperation;

public abstract class Loader<VAL, ID> {

    private final JavaPlugin plugin;
    
    private final Object lock = new Object();

    private final Map<ID, VAL> activeCache = new HashMap<>();
    private final Map<ID, Set<Accessor<VAL, ID>>> activeAccessors = new HashMap<>();

    private final Map<ID, State> states = new HashMap<>();
    private final Map<ID, List<Runnable>> pendingAccessDuringUnload = new HashMap<>();

    private final Set<Thread> illegalThreads = new HashSet<>();
    
    public Loader(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private void runAsync(Runnable r) {
        this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, () -> {
            r.run();
        });
    }

    private void runSync(Runnable r) {
        if (
            this.plugin.getServer().isPrimaryThread() 
            && !this.illegalThreads.contains(Thread.currentThread())
        ) {
            r.run(); // handle exception?
        } else {
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
                r.run();
            });
        }
    }

    public void access(
        @Nullable ID identifier,
        Accessor<VAL, ID> accessor,
        @Nullable Runnable onNotFound
    ) {
        this.runSync(() -> {
            synchronized (this.lock) {
                this.access0(identifier, accessor, onNotFound, true);
            }
        });
    }

    // Under Lock
    private void access0(
        @Nullable ID identifier,
        Accessor<VAL, ID> accessor,
        @Nullable Runnable onNotFound,
        boolean unloadIfStateIsUnknownAndIsFoundOrCreatedAndIfStateIsNullAndIfDoneDuringInit
    ) {
        State state = this.states.get(identifier);

        if (state == null) {
            this.accessOnUnknownState(identifier, accessor, onNotFound,
                unloadIfStateIsUnknownAndIsFoundOrCreatedAndIfStateIsNullAndIfDoneDuringInit
            );
        } else {
            this.accessOnKnownState(identifier, accessor, onNotFound, state);
        }
    }

    private boolean addAccessor(ID identifier, Accessor<VAL, ID> accessor) {
        Set<Accessor<VAL, ID>> a = this.activeAccessors.get(identifier);

        if (a == null) {
            a = new HashSet<>();
            this.activeAccessors.put(identifier, a);
        }

        return a.add(accessor);
    }

    private boolean removeAccessor(ID identifier, Accessor<VAL, ID> accessor) {
        Set<Accessor<VAL, ID>> a = this.activeAccessors.get(identifier);

        if (a == null) {
            return false;
        }

        if (a.remove(accessor)) {
            if (a.isEmpty()) {
                this.activeAccessors.remove(identifier);
            }

            return true;
        } else {
            return false;
        }
    }

    private boolean hasAccessor(ID identifier) {
        Set<Accessor<VAL, ID>> a = this.activeAccessors.get(identifier);

        if (a == null) {
            return false;
        }

        return !a.isEmpty();
    }

    private void addPendingAccessDuringUnload(ID identifier, Runnable r) {
        List<Runnable> p = this.pendingAccessDuringUnload.get(identifier);

        if (p == null) {
            p = new ArrayList<>();
            this.pendingAccessDuringUnload.put(identifier, p);
        }

        p.add(r);
    }

    private boolean hasPendingAccessDuringUnload(ID identifier) {
        List<Runnable> p = this.pendingAccessDuringUnload.get(identifier);

        if (p == null) {
            return false;
        } else {
            if (p.isEmpty()) {
                this.pendingAccessDuringUnload.remove(identifier);
                return false;
            } else {
                return true;
            }
        }
    }

    // Under Lock
    private void unload(ID identifier, VAL value) {
        this.states.put(identifier, State.UNLOADING);
        SaveOperation<VAL, ID> op = this.opSave(identifier, value);
        op.runPart1(); // handle exception?

        this.runAsync(() -> {
            op.runPart2(); // handle exception?

            this.runSync(() -> {
                synchronized (this.lock) {
                    this.states.remove(identifier);

                    if (this.hasPendingAccessDuringUnload(identifier)) {
                        List<Runnable> p = this.pendingAccessDuringUnload.get(identifier);
                        this.pendingAccessDuringUnload.put(identifier, new ArrayList<>());

                        for (Runnable r : p) {
                            r.run();
                        }

                        if (!this.hasAccessor(identifier)) {
                            this.unload(identifier, value);
                        }
                    }
                }
            });
        });
    }

    // Under Lock
    private void accessOnUnknownState(
        @Nullable ID identifier,
        Accessor<VAL, ID> accessor,
        @Nullable Runnable onNotFound,
        boolean unloadIfFoundOrCreatedAndIfStateIsNullAndIfDoneDuringInit
    ) {
        this.runAsync(() -> {
            AccessOperation<VAL, ID> op = this.opAccess(identifier);
            boolean foundOrCreated = op.runPart1(); // handle exception?

            if (foundOrCreated) {
                this.runSync(() -> {
                    synchronized (this.lock) {
                        State state = this.states.get(op.id());

                        if (state == null) {
                            // Make active
                            op.runPart2(); // handle exception?

                            boolean doneDuringInit = this.provisionAccess(
                                accessor, 
                                op.id(), 
                                op.value()
                            ); // handle exception?

                            if (doneDuringInit && unloadIfFoundOrCreatedAndIfStateIsNullAndIfDoneDuringInit) {
                                this.unload(op.id(), op.value());
                            } else {
                                this.states.put(op.id(), (state = State.ACTIVE));
                                this.activeCache.put(op.id(), op.value());
                            }
                        } else {
                            this.accessOnKnownState(op.id(), accessor, onNotFound, state);
                        }
                    }
                    
                });
            } else {
                if (onNotFound != null) {
                    this.runSync(onNotFound);
                }
            }
        });
    }

    private boolean provisionAccess(Accessor<VAL, ID> accessor, ID identifier, VAL value) {
        final Object doneLock = new Object();
        AtomicBoolean isDone = new AtomicBoolean();
        AtomicBoolean initSuccess = new AtomicBoolean();
        AtomicBoolean doneDuringInit = new AtomicBoolean();
        AtomicBoolean isInit = new AtomicBoolean();

        Access<VAL, ID> access = new Access<>() {

            @Override
            public VAL value() {
                return value;
            }

            @Override
            public ID id() {
                return identifier;
            }

            @Override
            public void done() {
                synchronized (doneLock) {
                    if (isDone.get()) {
                        return;
                    }

                    isDone.set(true);

                    if (!initSuccess.get()) {
                        return;
                    } else if (isInit.get()) {
                        doneDuringInit.set(true);
                    } else {
                        synchronized (Loader.this.lock) {
                            Loader.this.removeAccessor(identifier, accessor);

                            if (!Loader.this.hasAccessor(identifier)) {
                                Loader.this.unload(identifier, value);
                            }
                        }
                    }
                }
            }
        };

        isInit.set(true);
        try {
            this.illegalThreads.add(Thread.currentThread());
            accessor.init(access); // handle exception?
            initSuccess.set(true);
        } finally {
            this.illegalThreads.remove(Thread.currentThread());
            isInit.set(false);
        }

        if (!doneDuringInit.get()) {
            this.addAccessor(identifier, accessor);
        }

        return doneDuringInit.get();
    }

    // Under Lock
    private void accessOnKnownState(
        @Nullable ID identifier,
        Accessor<VAL, ID> accessor,
        @Nullable Runnable onNotFound,
        State state
    ) {
        if (state == State.ACTIVE) {
            VAL value = this.activeCache.get(identifier);
            this.provisionAccess(accessor, identifier, value);
        } else if (state == State.UNLOADING) {
            this.addPendingAccessDuringUnload(identifier, () -> {
                this.access0(identifier, accessor, onNotFound, false);
            });
        }
    }

    public void access(
        @Nullable ID identifier,
        Accessor<VAL, ID> accessor
    ) {
        this.access(identifier, accessor, null);
    }

    protected abstract AccessOperation<VAL, ID> opAccess(@Nullable ID identifier);
    protected abstract SaveOperation<VAL, ID> opSave(ID identifier, VAL value);
    
}
