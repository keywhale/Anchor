package keywhale.bukkit.util.loader.op.exc;

public interface DeleteOperationExceptionHandler<ID> {

    public default void handleDefault(Exception exc) {
        //
    }

    public default void handle(DeleteOperationException exc, ID id) {
        this.handleDefault(exc);
    }

    public default void handle(RuntimeException exc, ID id) {
        this.handleDefault(exc);
    }

}
