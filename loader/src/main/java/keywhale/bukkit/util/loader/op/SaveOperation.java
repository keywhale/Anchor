package keywhale.bukkit.util.loader.op;

import keywhale.bukkit.util.loader.op.exc.OperationException;

public interface SaveOperation {
    public void runPart1() throws OperationException; // SYNC
    public void runPart2() throws OperationException; // ASYNC
}
