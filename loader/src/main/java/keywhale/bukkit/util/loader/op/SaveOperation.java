package keywhale.bukkit.util.loader.op;

public interface SaveOperation<VAL, ID> {
    public void runPart1(); // SYNC
    public void runPart2(); // ASYNC
}
