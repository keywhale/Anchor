package keywhale.bukkit.util.loader;

public interface Access<VAL, ID> {
    public VAL value();
    public ID id();
    public void done();
}
