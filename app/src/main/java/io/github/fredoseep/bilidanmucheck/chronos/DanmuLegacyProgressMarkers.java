package io.github.fredoseep.bilidanmucheck.chronos;

import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.MotionEvent;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.fredoseep.bilidanmucheck.DexKitHelper;

/** Uses PlayerDotSeekBar's existing point setter and native per-point color/radius. */
final class DanmuLegacyProgressMarkers {
    private static final String TAG = "DanmuJump";
    private static final String BINDING = "danmuJump.legacyMarkerBinding";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<WeakReference<View>> VIEWS = new ArrayList<>();
    private static volatile Data data;
    private static Constructor<?> pointConstructor;
    private static Method getter, setter;
    private static Field sourcePoints;
    private static boolean installed;

    private static final class Data {
        final String key;
        final long duration;
        final long[] targets;
        volatile boolean drawn;
        Data(String key, long duration, long[] targets) {
            this.key = key; this.duration = duration; this.targets = targets.clone();
        }
    }
    private static final class NativeInput {
        final int duration; final List<?> points;
        NativeInput(int duration, Object points) {
            this.duration = duration;
            this.points = points instanceof List<?> ? new ArrayList<>((List<?>) points) : new ArrayList<>();
        }
    }
    private static final class Binding {
        final Object manager;
        final float radius;
        List<Object> base;
        int nativeDuration;
        Data applied;
        boolean internal;
        Binding(Object manager, List<?> base, float density) {
            this.manager = manager; this.base = new ArrayList<>(base); this.radius = Math.max(2f, 3f * density);
        }
    }

    static void update(String key, long duration, long[] targets) {
        data = new Data(key, duration, targets);
        refresh();
    }
    static void clear() { data = null; refresh(); }
    static boolean wasDrawn(String key) { Data value = data; return value != null && value.key.equals(key) && value.drawn; }

    static void install(ClassLoader loader, DexKitHelper helper) {
        if (installed) return;
        try {
            Class<?> viewClass = XposedHelpers.findClass(helper.PROGRESS_VIEW_CLASS_NAME, loader);
            Class<?> point = XposedHelpers.findClass(helper.PROGRESS_POINT_CLASS_NAME, loader);
            Class<?> function = XposedHelpers.findClass("kotlin.jvm.functions.Function1", loader);
            pointConstructor = point.getDeclaredConstructor(int.class, int.class, boolean.class,
                    Integer.class, Float.class, function);
            pointConstructor.setAccessible(true);
            // Discover the native progress manager through the no-argument getter and (int,List) setter.
            for (Method candidate : viewClass.getDeclaredMethods()) {
                if (Modifier.isStatic(candidate.getModifiers()) || candidate.getParameterTypes().length != 0
                        || candidate.getReturnType().isPrimitive()) continue;
                Method possibleSetter = findSetter(candidate.getReturnType());
                if (possibleSetter == null) continue;
                if (getter != null) throw new IllegalStateException("Multiple native progress manager getters");
                getter = candidate; setter = possibleSetter;
            }
            if (getter == null) throw new NoSuchMethodException("Native progress manager getter");
            getter.setAccessible(true); setter.setAccessible(true);
            for (Field field : getter.getReturnType().getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && field.getType() == ArrayList.class) {
                    if (sourcePoints != null) throw new IllegalStateException("Multiple native source point lists");
                    sourcePoints = field;
                }
            }
            if (sourcePoints == null) throw new NoSuchFieldException("Native source point list");
            sourcePoints.setAccessible(true);
            XposedBridge.hookMethod(setter, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Binding binding = (Binding) XposedHelpers.getAdditionalInstanceField(param.thisObject, BINDING);
                    if (binding != null && binding.internal) return;
                    try {
                        NativeInput input = new NativeInput(((Number) param.args[0]).intValue(), param.args[1]);
                        XposedHelpers.setAdditionalInstanceField(param.thisObject, BINDING + ".input", input);
                        if (binding == null) return;
                        binding.nativeDuration = ((Number) param.args[0]).intValue();
                        binding.base = param.args[1] instanceof List<?> ? new ArrayList<>((List<?>) param.args[1]) : new ArrayList<>();
                        Data current = data;
                        if (binding.nativeDuration <= 0 && current != null)
                            param.args[0] = (int) Math.min(Integer.MAX_VALUE, current.duration);
                        param.args[1] = merge(binding, current);
                        binding.applied = current;
                    } catch (Throwable error) { Log.d(TAG, "[ProgressMarkers] legacy setter failed\n" + Log.getStackTraceString(error)); }
                }
            });
            XposedHelpers.findAndHookMethod(viewClass, "draw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try { apply((View) param.thisObject); }
                    catch (Throwable error) { Log.d(TAG, "[ProgressMarkers] legacy draw setup failed\n" + Log.getStackTraceString(error)); }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Binding binding = (Binding) XposedHelpers.getAdditionalInstanceField(param.thisObject, BINDING);
                    Data current = data;
                    if (!param.hasThrowable() && binding != null && current != null && binding.applied == current
                            && current.targets.length > 0 && !current.drawn) {
                        current.drawn = true;
                        Log.d(TAG, "[ProgressMarkers] legacy native draw completed: video=" + current.key
                                + ", points=" + current.targets.length + ", view=" + param.thisObject.getClass().getName());
                    }
                }
            });
            installMagnet(viewClass);
            installed = true;
            Log.d(TAG, "[ProgressMarkers] legacy renderer installed: view=" + viewClass.getName()
                    + ", manager=" + getter.getReturnType().getName() + ", point=" + point.getName());
        } catch (Throwable error) { Log.d(TAG, "[ProgressMarkers] legacy renderer unavailable\n" + Log.getStackTraceString(error)); }
    }

    private static final class MagnetState {
        boolean active;
        String key;
        long target = -1;
        void reset() { active = false; key = null; target = -1; }
    }

    private static void installMagnet(Class<?> viewClass) {
        try {
            // Hook the framework handler after the app has applied its own thumb-relative offset.
            // Changing coordinates in PlayerDotSeekBar.onTouchEvent would happen too early.
            Method touch = null;
            for (Class<?> parent = viewClass.getSuperclass(); parent != null; parent = parent.getSuperclass()) {
                try { touch = parent.getDeclaredMethod("onTouchEvent", MotionEvent.class); break; }
                catch (NoSuchMethodException ignored) {}
            }
            if (touch == null) throw new NoSuchMethodException("Framework seek touch handler");
            XposedBridge.hookMethod(touch, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!viewClass.isInstance(param.thisObject)) return;
                    try {
                        View view = (View) param.thisObject;
                        MotionEvent event = (MotionEvent) param.args[0];
                        MagnetState state = (MagnetState) XposedHelpers.getAdditionalInstanceField(view, BINDING + ".magnet");
                        if (state == null) {
                            state = new MagnetState();
                            XposedHelpers.setAdditionalInstanceField(view, BINDING + ".magnet", state);
                        }
                        int action = event.getActionMasked();
                        Data current = data;
                        if (!view.isEnabled() || current == null || current.targets.length == 0
                                || event.getPointerCount() != 1 || action == MotionEvent.ACTION_CANCEL) {
                            state.reset(); return;
                        }
                        if (action == MotionEvent.ACTION_DOWN) {
                            state.reset(); state.active = true; state.key = current.key; return;
                        }
                        if (!state.active || !current.key.equals(state.key)) { state.reset(); return; }
                        if (action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_UP) return;
                        long previous = state.target;
                        boolean rtl = mirrored(view);
                        float width = view.getWidth() - view.getPaddingLeft() - view.getPaddingRight();
                        long target = selectMagnet(current, state, event.getX(), view.getPaddingLeft(), width,
                                view.getResources().getDisplayMetrics().density, rtl);
                        if (target >= 0 && width > 0) {
                            float fraction = (float) ((double) target / current.duration);
                            float x = view.getPaddingLeft() + (rtl ? 1f - fraction : fraction) * width;
                            MotionEvent copy = MotionEvent.obtain(event);
                            copy.setLocation(x, event.getY());
                            param.args[0] = copy;
                            param.setObjectExtra(BINDING + ".event", copy);
                            if (previous != target || action == MotionEvent.ACTION_UP)
                                Log.d(TAG, "[ProgressMagnet] " + (action == MotionEvent.ACTION_UP ? "release" : "capture")
                                        + " targetMs=" + target + ", video=" + current.key);
                        }
                        if (action == MotionEvent.ACTION_UP) state.reset();
                    } catch (Throwable error) { Log.d(TAG, "[ProgressMagnet] touch failed: " + error); }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    MotionEvent copy = (MotionEvent) param.getObjectExtra(BINDING + ".event");
                    if (copy != null) copy.recycle();
                }
            });
            Log.d(TAG, "[ProgressMagnet] installed: capture=14dp/5s, release=22dp/8s");
        } catch (Throwable error) { Log.d(TAG, "[ProgressMagnet] unavailable\n" + Log.getStackTraceString(error)); }
    }

    /** Require both screen distance and time distance; hysteresis lets the user pull away smoothly. */
    private static long selectMagnet(Data current, MagnetState state, float x, float left,
                                     float width, float density, boolean rtl) {
        if (width <= 0 || current.duration <= 0 || !Float.isFinite(x)) { state.target = -1; return -1; }
        float fraction = (x - left) / width;
        double time = (rtl ? 1d - fraction : fraction) * current.duration;
        if (state.target >= 0) {
            boolean exists = false;
            for (long target : current.targets) if (target == state.target) { exists = true; break; }
            double pixels = Math.abs(time - state.target) / current.duration * width;
            if (exists && pixels <= 22f * density && Math.abs(time - state.target) <= 8000) return state.target;
        }
        long nearest = -1;
        double best = Double.POSITIVE_INFINITY;
        for (long target : current.targets) {
            double delta = Math.abs(time - target);
            if (delta <= 5000 && delta / current.duration * width <= 14f * density && delta < best) {
                best = delta; nearest = target;
            }
        }
        state.target = nearest;
        return nearest;
    }

    private static boolean mirrored(View view) {
        if (view.getLayoutDirection() != View.LAYOUT_DIRECTION_RTL) return false;
        for (Class<?> owner = view.getClass(); owner != null; owner = owner.getSuperclass()) {
            try {
                Field field = owner.getDeclaredField("mMirrorForRtl");
                field.setAccessible(true);
                return field.getBoolean(view);
            } catch (NoSuchFieldException ignored) {}
            catch (Throwable ignored) { break; }
        }
        return true;
    }

    private static Method findSetter(Class<?> owner) {
        Method result = null;
        for (Method method : owner.getDeclaredMethods()) {
            Class<?>[] args = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers()) && method.getReturnType() == void.class
                    && args.length == 2 && args[0] == int.class && args[1] == List.class) {
                if (result != null) return null;
                result = method;
            }
        }
        return result;
    }

    private static List<Object> merge(Binding binding, Data current) throws Exception {
        List<Object> result = new ArrayList<>(binding.base);
        if (current == null || current.duration <= 0) return result;
        int maximum = binding.nativeDuration > 0 ? binding.nativeDuration : (int) Math.min(Integer.MAX_VALUE, current.duration);
        for (long target : current.targets) {
            int position = (int) Math.round((double) target * maximum / current.duration);
            if (position <= 0 || position >= maximum) continue;
            result.add(pointConstructor.newInstance(position, 0, false, Integer.valueOf(0xffff4444),
                    Float.valueOf(binding.radius), null));
        }
        return result;
    }

    private static void apply(View view) throws Exception {
        Binding binding = (Binding) XposedHelpers.getAdditionalInstanceField(view, BINDING);
        if (binding == null) {
            Object manager = getter.invoke(view);
            Object source = sourcePoints.get(manager);
            NativeInput input = (NativeInput) XposedHelpers.getAdditionalInstanceField(manager, BINDING + ".input");
            binding = new Binding(manager, input != null ? input.points
                    : source instanceof List<?> ? (List<?>) source : new ArrayList<>(),
                    view.getResources().getDisplayMetrics().density);
            if (input != null) binding.nativeDuration = input.duration;
            XposedHelpers.setAdditionalInstanceField(view, BINDING, binding);
            XposedHelpers.setAdditionalInstanceField(manager, BINDING, binding);
            synchronized (VIEWS) { VIEWS.add(new WeakReference<>(view)); }
            Log.d(TAG, "[ProgressMarkers] legacy seekbar observed: " + view.getClass().getName());
        }
        Data current = data;
        if (binding.applied == current) return;
        int duration = binding.nativeDuration > 0 ? binding.nativeDuration
                : current == null ? 0 : (int) Math.min(Integer.MAX_VALUE, current.duration);
        binding.internal = true;
        try { setter.invoke(binding.manager, duration, merge(binding, current)); binding.applied = current; }
        finally { binding.internal = false; }
        view.invalidate();
        if (current != null) Log.d(TAG, "[ProgressMarkers] legacy points applied: video=" + current.key
                + ", points=" + current.targets.length + ", nativeMaximum=" + duration);
    }

    private static void refresh() {
        MAIN.post(() -> {
            synchronized (VIEWS) {
                Iterator<WeakReference<View>> iterator = VIEWS.iterator();
                while (iterator.hasNext()) {
                    View view = iterator.next().get();
                    if (view == null) { iterator.remove(); continue; }
                    try { if (view.isShown()) apply(view); }
                    catch (Throwable error) { Log.d(TAG, "[ProgressMarkers] legacy refresh failed: " + error); }
                }
            }
        });
    }
}
