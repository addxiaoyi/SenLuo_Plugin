package net.kaleidoscope.cookery.nms;

import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

/** The two verified native mineBlock implementations have different durability gates. */
public final class NativeMiningImplementation {
    private final Class<?> declaringClass;
    private final MethodType signature;
    private final boolean shears;

    private NativeMiningImplementation(Method method, boolean shears) {
        this.declaringClass = method.getDeclaringClass();
        this.signature = MethodType.methodType(method.getReturnType(), method.getParameterTypes());
        this.shears = shears;
    }

    /** Inherited methods are allowed; an unknown override cannot inherit a trusted mining receipt. */
    public static NativeMiningImplementation inspect(Class<?> actualItemClass, Class<?> itemClass,
                                                      Class<?> shearsClass, Class<?>... parameters) {
        final Method method;
        try { method = actualItemClass.getMethod("mineBlock", parameters); }
        catch (NoSuchMethodException failure) {
            throw new IllegalArgumentException("Missing verified native mineBlock signature: " + actualItemClass.getName(), failure);
        }
        Class<?> owner = method.getDeclaringClass();
        if (method.getReturnType() != boolean.class || (owner != itemClass && owner != shearsClass))
            throw new IllegalArgumentException("Unsupported native mineBlock override: " + actualItemClass.getName());
        return new NativeMiningImplementation(method, owner == shearsClass);
    }

    public int baseWear(boolean preventsBlockDrops, int rawToolWear, float destroySpeed, boolean fire) {
        if (preventsBlockDrops || rawToolWear <= 0 || (shears ? fire : destroySpeed == 0)) return 0;
        return rawToolWear;
    }

    public boolean matches(StackWalker.StackFrame frame) {
        return frame.getDeclaringClass() == declaringClass && frame.getMethodName().equals("mineBlock")
                && frame.getMethodType().equals(signature);
    }

    /** A rejected break cannot turn an external listener's inventory changes into our own commit. */
    public static NativeSickleAccess.Outcome rejectedDestruction(NativeMiningDamageTracker.Decision decision) {
        if (decision.observedEvent() || decision.observedBreak())
            return NativeSickleAccess.Outcome.partial("native break reported no commit after attributed tool damage or break");
        if (!decision.completeObservation())
            return NativeSickleAccess.Outcome.partial("native break reported no commit without a complete tool observation: " + decision.reason());
        return NativeSickleAccess.Outcome.rejected();
    }
}
