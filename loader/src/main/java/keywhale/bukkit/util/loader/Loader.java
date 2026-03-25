package keywhale.bukkit.util.loader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

import keywhale.bukkit.util.loader.op.AccessOperation;
import keywhale.bukkit.util.loader.op.DeleteOperation;
import keywhale.bukkit.util.loader.op.SaveOperation;
import keywhale.bukkit.util.loader.op.exc.OperationException;
import keywhale.bukkit.util.loader.op.exc.OperationExceptionHandler;

public abstract class Loader<ID, VAL> {

    private final JavaPlugin plugin;
    private final Object lock = new Object();
    private final Map<ID, StateTracker<ID, VAL>> trackers = new HashMap<>();
    private final Set<Runnable> pendingRunnables = new HashSet<>();
    private boolean isShutdown = false;
    private boolean isExpediting = false;

    public Loader(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private void runAsync(Runnable r) {
        synchronized (this.lock) {
            this.pendingRunnables.add(r);
            if (this.isExpediting) return;
        }

        if (this.plugin.isEnabled()) {
            this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, () -> {
                boolean shouldRun;
                synchronized (this.lock) {
                    shouldRun = this.pendingRunnables.remove(r);
                }

                if (shouldRun) {
                    r.run();
                }
            });
        }
    }

    private void runSync(Runnable r) {
        synchronized (this.lock) {
            this.pendingRunnables.add(r);
            if (this.isExpediting) return;
        }

        if (this.plugin.isEnabled()) {
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
                boolean shouldRun;
                synchronized (this.lock) {
                    shouldRun = this.pendingRunnables.remove(r);
                }

                if (shouldRun) {
                    r.run();
                }
            });
        }
    }

    public static class ShuttingDownException extends IllegalStateException {}

    private void checkShutdown() {
        if (this.isShutdown) {
            throw new ShuttingDownException();
        }
    }

    protected OperationExceptionHandler<ID, VAL> onException() {
        return new OperationExceptionHandler<>() {
            @Override
            public void handleDefault(Exception exc) {
                Loader.this.plugin.getLogger().log(Level.SEVERE, "Operation failed", exc);
            }
        };
    }

    public void access(
        @Nullable ID identifier,
        Accessor<ID, VAL> accessor,
        @Nullable Runnable onNotFound
    ) {
        synchronized (this.lock) {
            this.checkShutdown();

            this.runSync(() -> {
                synchronized (this.lock) {
                    this.access0(identifier, accessor, onNotFound);
                }
            });
        }
    }

    public void access(@Nullable ID identifier, Accessor<ID, VAL> accessor) {
        this.access(identifier, accessor, null);
    }

    public void shutdownAndExpedite() {
        synchronized (this.lock) {
            if (this.isShutdown) {
                return;
            }

            this.isShutdown = true;
            this.isExpediting = true;

            Map<ID, StateTracker<ID, VAL>> trackersCopy = new HashMap<>(this.trackers);

            for (StateTracker<ID, VAL> tracker : trackersCopy.values()) {
                tracker.shutdown();
            }

            RuntimeExceptionRoller roller = new RuntimeExceptionRoller();

            while (!this.pendingRunnables.isEmpty()) {
                var pendingRunnablesCopy = new HashSet<>(this.pendingRunnables);
                this.pendingRunnables.clear();

                for (var pr : pendingRunnablesCopy) {
                    roller.exec(pr);
                }
            }

            this.isExpediting = false;
            roller.raise();
        }
    }

    public void delete(ID identifier) {
        synchronized (this.lock) {
            this.checkShutdown();

            this.runSync(() -> {
                synchronized (this.lock) {
                    StateTracker<ID, VAL> tracker = this.trackers.get(identifier);

                    if (tracker == null) {
                        this.deleteReplaceTracker(identifier);
                    } else {
                        tracker.delete();
                    }
                }
            });
        }
    }

    // Under Lock
    private void deleteReplaceTracker(ID identifier) {
        DeletingStateTracker deleteTracker = new DeletingStateTracker();
        this.trackers.put(identifier, deleteTracker);

        this.runAsync(() -> {
            DeleteOperation op = this.opDelete(identifier);
            try {
                op.run();
            } catch (OperationException e) {
                Loader.this.onException().handleDelete(e, identifier);
            } catch (RuntimeException e) {
                Loader.this.onException().handleDelete(e, identifier);
            } finally {
                this.runSync(() -> {
                    synchronized (this.lock) {
                        this.trackers.remove(identifier);
                    }
                });
            }
        });
    }

    // Under Lock
    private void access0(
        @Nullable ID identifier,
        Accessor<ID, VAL> accessor,
        @Nullable Runnable onNotFound
    ) {
        StateTracker<ID, VAL> tracker = this.trackers.get(identifier);

        if (tracker == null) {
            this.accessOnUnknownState(identifier, accessor, onNotFound);
        } else {
            tracker.access(accessor, onNotFound);
        }
    }

    // Under Lock
    // Only called while active
    private void unloadOnSyncThread(ID identifier, VAL value) {
        UnloadingStateTracker tracker = new UnloadingStateTracker(identifier, value);
        this.trackers.put(identifier, tracker);

        this.unloadAfterTracker(identifier, value, tracker);
    }

    // Under Lock
    private void unloadFromAnyThread(ID identifier, VAL value) {
        UnloadingStateTracker tracker = new UnloadingStateTracker(identifier, value);
        this.trackers.put(identifier, tracker);

        if (this.plugin.getServer().isPrimaryThread()) {
            this.unloadAfterTracker(identifier, value, tracker);
        } else {
            this.runSync(() -> this.unloadAfterTracker(identifier, value, tracker));
        }
    }

    private void unloadAfterTracker(ID identifier, VAL value, UnloadingStateTracker tracker) {
        AtomicBoolean failedPart1 = new AtomicBoolean();

        SaveOperation op = this.opSave(identifier, value);
        try {
            op.runPart1();
        } catch (OperationException e) {
            Loader.this.onException().handleSavePart1(e, identifier, value);
            failedPart1.set(true);
        } catch (RuntimeException e) {
            Loader.this.onException().handleSavePart1(e, identifier, value);
            failedPart1.set(true);
        }

        this.runAsync(() -> {
            if (!failedPart1.get()) {
                try {
                    op.runPart2();
                } catch (OperationException e) {
                    Loader.this.onException().handleSavePart2(e, identifier, value);
                } catch (RuntimeException e) {
                    Loader.this.onException().handleSavePart2(e, identifier, value);
                }
            }

            this.runSync(() -> {
                synchronized (this.lock) {
                    tracker.onComplete();
                }
            });
        });
    }

    // Under Lock
    private void accessOnUnknownState(
        @Nullable ID identifier,
        Accessor<ID, VAL> accessor,
        @Nullable Runnable onNotFound
    ) {
        this.runAsync(() -> {
            AccessOperation<ID, VAL> op = this.opAccess(identifier);
            boolean foundOrCreated;
            try {
                foundOrCreated = op.runPart1();
            } catch (OperationException e) {
                Loader.this.onException().handleAccessPart1(e);
                return;
            } catch (RuntimeException e) {
                Loader.this.onException().handleAccessPart1(e);
                return;
            }

            if (foundOrCreated) {
                this.runSync(() -> {
                    synchronized (this.lock) {
                        StateTracker<ID, VAL> tracker = this.trackers.get(op.id());

                        if (tracker == null) {
                            // Make active
                            try {
                                op.runPart2();
                            } catch (OperationException e) {
                                Loader.this.onException().handleAccessPart2(e, op.id());
                                return;
                            } catch (RuntimeException e) {
                                Loader.this.onException().handleAccessPart2(e, op.id());
                                return;
                            }

                            ActiveStateTracker activeTracker
                                = new ActiveStateTracker(op.id(), op.value());

                            boolean doneDuringInit = activeTracker.provisionAccess(accessor);

                            if (doneDuringInit) {
                                Loader.this.unloadOnSyncThread(op.id(), op.value());
                            } else {
                                Loader.this.trackers.put(op.id(), activeTracker);
                            }
                        } else {
                            tracker.access(accessor, onNotFound);
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

    private class PendingAccessRequest {
        Accessor<ID, VAL> accessor;
        @Nullable Runnable onNotFound;
    }

    private static class RuntimeExceptionRoller {
        private RuntimeException top;

        public void add(RuntimeException runtimeException) {
            if (this.top == null) {
                this.top = runtimeException;
            } else {
                this.top.addSuppressed(runtimeException);
            }
        }

        public void raise() {
            if (this.top != null) {
                throw this.top;
            }
        }

        public void exec(Runnable r) {
            try {
                r.run();
            } catch (RuntimeException re) {
                this.add(re);
            }
        }
    }

    private interface StateTracker<ID, VAL> {
        void access(Accessor<ID, VAL> accessor, @Nullable Runnable onNotFound);
        void delete();
        void shutdown();
    }

    private class ActiveStateTracker implements StateTracker<ID, VAL> {

        private final ID identifier;
        private final VAL value;

        private final Set<Accessor<ID, VAL>> accessors = new HashSet<>();

        private boolean isShuttingDown = false;

        ActiveStateTracker(ID identifier, VAL value) {
            this.identifier = identifier;
            this.value = value;
        }

        @Override
        public void access(Accessor<ID, VAL> accessor, @Nullable Runnable onNotFound) {
            this.provisionAccess(accessor);
        }

        public void loadPending(List<PendingAccessRequest> pars) {
            var roller = new RuntimeExceptionRoller();
            for (var par : pars) {
                roller.exec(() -> this.provisionAccess(par.accessor));
            }

            try {
                roller.raise();
            } finally {
                if (this.accessors.isEmpty()) {
                    Loader.this.unloadOnSyncThread(this.identifier, this.value);
                }
            }
        }

        private boolean isActive() {
            return ((Loader.this.trackers.get(this.identifier) == this) && !this.isShuttingDown);
        }

        private boolean provisionAccess(Accessor<ID, VAL> accessor) {
            AtomicBoolean isDone = new AtomicBoolean();
            AtomicBoolean initSuccess = new AtomicBoolean();
            AtomicBoolean doneDuringInit = new AtomicBoolean();
            AtomicBoolean isInit = new AtomicBoolean();

            final Object doneLock = new Object();
            final var ast = this;

            Access<ID, VAL> access = new Access<>() {

                @Override
                public VAL value() {
                    return ast.value;
                }

                @Override
                public ID id() {
                    return ast.identifier;
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
                                if (ast.isActive()) {
                                    ast.accessors.remove(accessor);

                                    if (ast.accessors.isEmpty()) {
                                        Loader.this.unloadFromAnyThread(ast.identifier, ast.value);
                                    }
                                }
                            }
                        }
                    }
                }
            };

            isInit.set(true);
            try {
                accessor.init(access);
                initSuccess.set(true);
            } finally {
                isInit.set(false);
            }

            if (!doneDuringInit.get()) {
                this.accessors.add(accessor);
            }

            return doneDuringInit.get();
        }

        @Override
        public void delete() {
            Loader.this.deleteReplaceTracker(this.identifier);

            try {
                var roller = new RuntimeExceptionRoller();
                for (var accessor : this.accessors) {
                    roller.exec(accessor::cancel);
                }
                roller.raise();
            } finally {
                this.accessors.clear();
            }
        }

        @Override
        public void shutdown() {
            this.isShuttingDown = true;

            try {
                var roller = new RuntimeExceptionRoller();
                for (var accessor : this.accessors) {
                    roller.exec(accessor::cancel);
                }
                roller.raise();
            } finally {
                this.accessors.clear();
                Loader.this.unloadOnSyncThread(this.identifier, this.value);
            }
        }

    }

    private class UnloadingStateTracker implements StateTracker<ID, VAL> {

        private final ID identifier;
        private final VAL value;

        private final List<PendingAccessRequest> pendingAccess = new ArrayList<>();

        private boolean pendingDelete = false;
        private boolean pendingShutdown = false;

        UnloadingStateTracker(ID identifier, VAL value) {
            this.identifier = identifier;
            this.value = value;
        }

        @Override
        public void access(Accessor<ID, VAL> accessor, @Nullable Runnable onNotFound) {
            var par = new PendingAccessRequest(); {
                par.accessor = accessor;
                par.onNotFound = onNotFound;
            }

            this.pendingAccess.add(par);
        }

        public void onComplete() {
            Loader.this.trackers.remove(this.identifier);

            if (this.pendingShutdown) {
                this.pendingAccess.clear();
                this.pendingDelete = false;
            } else if (this.pendingDelete) {
                try {
                    var roller = new RuntimeExceptionRoller();

                    for (var par : this.pendingAccess) {
                        if (par.onNotFound != null) {
                            roller.exec(par.onNotFound);
                        }
                    }

                    roller.raise();
                } finally {
                    Loader.this.deleteReplaceTracker(this.identifier);
                }
            } else if (!this.pendingAccess.isEmpty()) {
                ActiveStateTracker activeTracker
                    = new ActiveStateTracker(this.identifier, this.value);
                Loader.this.trackers.put(this.identifier, activeTracker);

                activeTracker.loadPending(this.pendingAccess);
            }
        }

        @Override
        public void delete() {
            this.pendingDelete = true;
        }

        @Override
        public void shutdown() {
            this.pendingShutdown = true;
        }

    }

    private class DeletingStateTracker implements StateTracker<ID, VAL> {

        @Override
        public void access(Accessor<ID, VAL> accessor, @Nullable Runnable onNotFound) {
            // Into the void...
        }

        @Override
        public void delete() {
            // Into the void...
        }

        @Override
        public void shutdown() {
            // Into the void...
        }

    }

    public BukkitTask autoRelease(
        Access<ID, VAL> access,
        Supplier<Boolean> isAccessing,
        long intervalTicks
    ) {
        AtomicReference<BukkitTask> bt = new AtomicReference<>();
        BukkitTask task = this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, () -> {
            if (!isAccessing.get()) {
                bt.get().cancel();
                access.done();
            }
        }, intervalTicks, intervalTicks);
        bt.set(task);
        return task;
    }

    // This should NOT call any methods on Loader
    protected abstract AccessOperation<ID, VAL> opAccess(@Nullable ID identifier);
    // This should NOT call any methods on Loader
    protected abstract SaveOperation opSave(ID identifier, VAL value);
    // This should NOT call any methods on Loader
    protected abstract DeleteOperation opDelete(ID identifier);

}
