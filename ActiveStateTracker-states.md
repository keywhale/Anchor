# ActiveStateTracker State Machine

| State | `access(op, accessor)` | `delete(op)` | `shutdown()` | `done()` not last | `done()` last |
|---|---|---|---|---|---|
| **Active** | `provisionAccess(accessor)` | → **Cancelling→Delete**; call `cancel()` on all | → **Cancelling→Shutdown**; call `cancel()` on all | remove from `accessors` | if `anyRequiresSave`: start unload cycle; else `trackers.remove()` |
| **Cancelling→Delete** | queue `AccessRequest(op, accessor)` in `pendingAccess` | no-op | → **Cancelling→Shutdown** | remove from `accessors` | `deleteReplaceTracker()` with `pendingAccess`; after delete completes, each request routed through `Loader.access(id, op, accessor)` |
| **Cancelling→Shutdown** | no-op | no-op | no-op | remove from `accessors` | if `anyRequiresSave`: start unload cycle; else `trackers.remove()` |

Unload cycle: create `UnloadingStateTracker`, put in `trackers`, call `save()`, call `unloadTracker.start()`.

## Edge Cases

- **Transition from Cancelling→Delete to Cancelling→Shutdown** — the `pendingAccess` list from `DeletingSubstate` is silently dropped; `ShutdownSubstate` has no pending accesses to manage.
