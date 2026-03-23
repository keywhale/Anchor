package keywhale.bukkit.util.loader.op.exc;

public interface SaveOperationExceptionHandler<ID, VAL> {

    public default void handleDefault(Exception exc) {
        //
    }

    public default void handlePart1(Part1SaveOperationException exc, ID id, VAL value) {
        this.handleDefault(exc);
    }

    public default void handlePart1(RuntimeException exc, ID id, VAL value) {
        this.handleDefault(exc);
    }

    public default void handlePart2(Part2SaveOperationException exc, ID id, VAL value) {
        this.handleDefault(exc);
    }

    public default void handlePart2(RuntimeException exc, ID id, VAL value) {
        this.handleDefault(exc);
    }

}
