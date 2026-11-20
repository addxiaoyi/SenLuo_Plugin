package net.kaleidoscope.cookery.ui.input;

final class DialogBridge {
    private DialogBridge() {}

    static void open(String implementation, Class<?>[] parameters, Object... arguments) {
        try {
            Class.forName(DialogBridge.class.getPackageName() + "." + implementation)
                    .getMethod("open", parameters).invoke(null, arguments);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            throw new IllegalStateException("Dialog failed: " + implementation, exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot load dialog: " + implementation, exception);
        }
    }
}
