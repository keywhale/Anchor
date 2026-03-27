package keywhale.bukkit.util.loader.op;

public interface SaveOperation {
    public void runPart1() throws OperationException; // SYNC
    public void runPart2() throws OperationException; // ASYNC
}
