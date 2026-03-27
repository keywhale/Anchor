# ActiveStateTracker State Machine

| State | `access()` | `delete()` | `shutdown()` | `done()` not last | `done()` last |
|---|---|---|---|---|---|
| **Active** | `provisionAccess()` | → **Cancelling→Delete**; call `cancel()` on all | → **Cancelling→Shutdown**; call `cancel()` on all | remove from `accessors` | if `anyRequiresSave`: `unloadFromAnyThread()`; else `trackers.remove()` |
| **Cancelling→Delete** | queue in `pendingAccess` | no-op | → **Cancelling→Shutdown** | remove from `accessors` | `deleteReplaceTracker()`; re-queue `pendingAccess` via `accessAfterDelete` after delete completes |
| **Cancelling→Shutdown** | no-op | no-op | no-op | remove from `accessors` | drop `pendingAccess`; if `anyRequiresSave`: `unloadFromAnyThread()`; else `trackers.remove()` |

## Edge Cases

- **Transition from Cancelling→Delete to Cancelling→Shutdown** — the `pendingAccess` list from `DeletingSubstate` is silently dropped; `ShutdownSubstate` has no pending accesses to manage.