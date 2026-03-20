package keywhale.bukkit.util.loader;

import java.util.function.Consumer;
import java.util.function.Function;

public interface Accessor<ID, VAL> {
    public void init(Access<ID, VAL> access);
    public void cancel();

    public static <ID, VAL> Accessor<ID, VAL> of (Consumer<Access<ID, VAL>> consumer) {
        return new Accessor<ID, VAL>() {

            @Override
            public void init(Access<ID, VAL> access) {
                consumer.accept(access);
            }

            @Override
            public void cancel() {
                // Do nothing
            }
            
        };
    }

    public static <ID, VAL> Accessor<ID, VAL> of(Function<Access<ID, VAL>, Runnable> function) {
        return new Accessor<ID, VAL>() {

            private Runnable cancel;

            @Override
            public void init(Access<ID, VAL> access) {
                this.cancel = function.apply(access);
            }

            @Override
            public void cancel() {
                this.cancel.run();
            }
            
        };
    }
}
