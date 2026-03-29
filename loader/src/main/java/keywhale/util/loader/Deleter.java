package keywhale.util.loader;

public interface Deleter {
    void done();
    default void onNotFound() {}

    public static Deleter of(Runnable onDone, Runnable onNotFound) {
        return new Deleter() {

            @Override
            public void done() {
                if (onDone != null) {
                    onDone.run();
                }
            }

            @Override
            public void onNotFound() {
                if (onNotFound != null) {
                    onNotFound.run();
                }
            }
            
        };
    }
}
