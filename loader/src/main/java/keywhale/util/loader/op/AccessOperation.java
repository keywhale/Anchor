package keywhale.util.loader.op;

import java.util.function.BiConsumer;

public interface AccessOperation<ID, VAL> {
    /*
    Dual-purpose: implementations may access an existing item or create a new one.

    `start` may be called from any thread, but the callback and `onNotFound` must
    each be called from the thread the implementation intends to run Accessor.init on.

    The callback is called with the resolved ID and value when the item is found
    or created.

    `onNotFound` is called when the item does not exist and was not created.
    Pass null for `onNotFound` if creation is guaranteed (not-found is impossible).
    */
    public void start(BiConsumer<ID, VAL> callback, Runnable onNotFound);
}