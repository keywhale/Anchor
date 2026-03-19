package keywhale.bukkit.util.loader;

import java.util.function.Function;

public interface Accessor<VAL, ID> {
    public void init(Access<VAL, ID> access);
    public void cancel();

    public static <VAL, ID> Accessor<VAL, ID> of(Function<Access<VAL, ID>, Runnable> fn) {
        return new Accessor<VAL, ID>() {

            private Runnable cancel;

            @Override
            public void init(Access<VAL, ID> access) {
                this.cancel = fn.apply(access);
            }

            @Override
            public void cancel() {
                this.cancel.run();
            }
            
        };
    }
}
