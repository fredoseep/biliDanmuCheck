package io.github.fredoseep.bilidanmucheck.chronos;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.fredoseep.bilidanmucheck.DexKitHelper;

/** Adds data to the app's native marker renderer; no overlay View and no seek interception. */
final class DanmuProgressMarkers {
    private static final String TAG = "DanmuJump";
    private static final long RED = 0xffff444400000000L; // Compose sRGB Color(0xffff4444).
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicLong EPOCH = new AtomicLong();
    private static final java.util.concurrent.ExecutorService RECORDS =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "DanmuMarkerRecords");
                thread.setDaemon(true);
                return thread;
            });
    private static final ThreadLocal<DrawState> DRAW = new ThreadLocal<>();
    private static volatile Points points;
    private static volatile WeakReference<Activity> activity = new WeakReference<>(null);
    private static Constructor<?> markerConstructor;
    private static Object markerKind;
    private static Class<?> uiStateClass;
    private static Field drawStateField, drawMarkersField;
    private static boolean installed;
    private static volatile long lastNativeDrawAt;

    private static final class Points {
        final String key;
        final long duration, epoch;
        final long[] targets;
        volatile boolean drawSeen, circleSeen, mismatchLogged;
        Points(String key, long duration, long epoch, long[] targets) {
            this.key = key; this.duration = duration; this.epoch = epoch; this.targets = targets;
        }
    }
    private static final class DrawState {
        final DrawState previous;
        Points data;
        boolean advertisement;
        DrawState(DrawState previous) { this.previous = previous; }
    }

    static void accept(String json) {
        try {
            JSONObject packet = new JSONObject(json);
            if ("clear".equals(packet.optString("event"))) {
                EPOCH.incrementAndGet(); points = null; DanmuLegacyProgressMarkers.clear(); redraw(); return;
            }
            long duration = packet.optLong("durationMs");
            JSONArray array = packet.optJSONArray("points");
            String aid = packet.optString("workId"), cid = packet.optString("videoId");
            if (duration <= 60000 || array == null || !aid.matches("[0-9]+") || !cid.matches("[0-9]+")) return;
            List<Long> valid = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                long target = array.getJSONObject(i).optLong("targetMs");
                if (target > 60000 && target < duration && !valid.contains(target)) valid.add(target);
            }
            Collections.sort(valid);
            long[] times = new long[valid.size()];
            for (int i = 0; i < times.length; i++) times[i] = valid.get(i);
            points = new Points(aid + "/" + cid, duration, EPOCH.get(), times);
            Points received = points;
            DanmuLegacyProgressMarkers.update(received.key, duration, times);
            Log.d(TAG, "[ProgressMarkers] videoId=" + cid + ", points=" + times.length
                    + ", scanned=" + packet.optInt("scannedSegments") + "/" + packet.optInt("totalSegments"));
            // Detailed voters remain in the existing DanmuJumpVote audit log.
            RECORDS.execute(() -> {
                Activity current = activity.get();
                if (current == null) return;
                try {
                    java.io.File directory = new java.io.File(current.getFilesDir(), "danmu_jump_logs");
                    if (!directory.isDirectory() && !directory.mkdirs()) return;
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                            new java.io.File(directory, "markers-" + cid + ".json"))) {
                        out.write(packet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                } catch (Throwable error) { Log.d(TAG, "[ProgressMarkers] record failed: " + error); }
            });
            redraw();
            if (times.length > 0) MAIN.postDelayed(() -> {
                if (points != received || received.circleSeen || DanmuLegacyProgressMarkers.wasDrawn(received.key)) return;
                Log.d(TAG, "[ProgressMarkers] draw pending: video=" + received.key
                        + ", nativeDrawSeen=" + received.drawSeen
                        + ", lastNativeDrawAt=" + lastNativeDrawAt
                        + "; show the player controls to test the seek bar");
            }, 3000L);
        } catch (Throwable error) { Log.d(TAG, "[ProgressMarkers] packet failed\n" + Log.getStackTraceString(error)); }
    }

    static void install(ClassLoader loader, DexKitHelper helper) {
        DanmuLegacyProgressMarkers.install(loader, helper);
        if (installed) return;
        try {
            uiStateClass = XposedHelpers.findClass(helper.PROGRESS_UI_STATE_CLASS_NAME, loader);
            Class<?> marker = XposedHelpers.findClass(helper.PROGRESS_MARKER_UI_CLASS_NAME, loader);
            Class<?> kind = XposedHelpers.findClass(
                    "com.bilibili.ship.theseus2.united.player.overlay.PlayerProgressMarkerKind", loader);
            markerKind = XposedHelpers.getStaticObjectField(kind, "Default");
            markerConstructor = marker.getDeclaredConstructor(long.class, float.class, float.class,
                    kind, String.class, String.class);
            markerConstructor.setAccessible(true);
            Class<?> draw = XposedHelpers.findClass(helper.PROGRESS_DRAW_CLASS_NAME, loader);
            drawStateField = uniqueField(draw, uiStateClass);
            drawMarkersField = uniqueField(draw, List.class);
            Class<?> drawScope = XposedHelpers.findClass("androidx.compose.ui.graphics.drawscope.DrawScope", loader);
            Method circle = null;
            for (Method method : drawScope.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (Modifier.isStatic(method.getModifiers()) && method.getName().startsWith("drawCircle-")
                        && method.getName().endsWith("$default") && types.length == 10
                        && types[0] == drawScope && types[1] == long.class && types[2] == float.class) {
                    if (circle != null) throw new IllegalStateException("Multiple native circle methods");
                    circle = method;
                }
            }
            if (circle == null) throw new NoSuchMethodException("Native marker circle renderer");
            XposedBridge.hookMethod(circle, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    DrawState state = DRAW.get();
                    if (state != null && state.advertisement) {
                        param.args[1] = RED;
                        state.advertisement = false;
                        if (state.data != null && !state.data.circleSeen) {
                            state.data.circleSeen = true;
                            Log.d(TAG, "[ProgressMarkers] native red marker draw reached: video="
                                    + state.data.key + ", points=" + state.data.targets.length);
                        }
                    }
                }
            });
            XposedHelpers.findAndHookMethod(draw, "invoke", Object.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    DrawState state = new DrawState(DRAW.get());
                    DRAW.set(state);
                    lastNativeDrawAt = android.os.SystemClock.uptimeMillis();
                    try { prepareDraw(param.thisObject, state); }
                    catch (Throwable error) {
                        Points data = points;
                        if (data != null && !data.mismatchLogged) {
                            data.mismatchLogged = true;
                            Log.d(TAG, "[ProgressMarkers] draw injection failed\n" + Log.getStackTraceString(error));
                        }
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    DrawState state = DRAW.get();
                    if (state == null || state.previous == null) DRAW.remove(); else DRAW.set(state.previous);
                }
            });
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) { activity = new WeakReference<>((Activity) param.thisObject); }
            });
            installed = true;
            Log.d(TAG, "[ProgressMarkers] native renderer installed: " + draw.getName());
        } catch (Throwable error) {
            Log.d(TAG, "[ProgressMarkers] native renderer unavailable\n" + Log.getStackTraceString(error));
        }
    }

    private static Field uniqueField(Class<?> owner, Class<?> type) throws Exception {
        Field result = null;
        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != type) continue;
            if (result != null) throw new IllegalStateException("Multiple " + type.getName() + " fields");
            result = field;
        }
        if (result == null) throw new NoSuchFieldException(owner.getName() + ": " + type.getName());
        result.setAccessible(true);
        return result;
    }

    /** Draw lambdas can be remembered by Compose before JS sends its first points packet. */
    private static void prepareDraw(Object renderer, DrawState state) throws Exception {
        Object value = drawMarkersField.get(renderer);
        if (!(value instanceof List<?>)) return;
        List<?> original = value instanceof MarkerList ? ((MarkerList) value).original : (List<?>) value;
        Points data = points;
        if (data == null || data.targets.length == 0) {
            if (value instanceof MarkerList) drawMarkersField.set(renderer, original);
            return;
        }
        data.drawSeen = true;
        long duration = durationMillis(drawStateField.get(renderer));
        // The JS work info is authoritative while the UI state's boxed Duration is not ready.
        if (duration <= 0) duration = data.duration;
        if (Math.abs(data.duration - duration) > 1000) {
            if (value instanceof MarkerList) drawMarkersField.set(renderer, original);
            if (!data.mismatchLogged) {
                data.mismatchLogged = true;
                Log.d(TAG, "[ProgressMarkers] duration mismatch: nativeMs=" + duration
                        + ", packetMs=" + data.duration + ", video=" + data.key);
            }
            return;
        }
        // Refresh the captured list at draw time, never freeze an epoch at construction time.
        if (!(value instanceof MarkerList) || ((MarkerList) value).data != data
                || ((MarkerList) value).duration != duration) {
            drawMarkersField.set(renderer, new MarkerList(original, duration, data));
            Log.d(TAG, "[ProgressMarkers] native marker list attached: video=" + data.key
                    + ", points=" + data.targets.length + ", durationMs=" + duration);
        }
        state.data = data;
    }

    private static long durationMillis(Object ui) {
        if (ui == null) return -1;
        try {
            for (Field field : uiStateClass.getDeclaredFields()) {
                if (!field.getType().getName().equals("kotlin.time.Duration")) continue;
                field.setAccessible(true); Object boxed = field.get(ui);
                if (boxed == null) return -1;
                Method unbox = boxed.getClass().getDeclaredMethod("unbox-impl");
                unbox.setAccessible(true);
                long encoded = ((Number) unbox.invoke(boxed)).longValue();
                return (encoded & 1L) == 0 ? (encoded >> 1) / 1000000L : encoded >> 1;
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static final class MarkerList extends AbstractList<Object> {
        private final List<?> original;
        private final long duration;
        private final Points data;
        MarkerList(List<?> original, long duration, Points data) { this.original = original; this.duration = duration; this.data = data; }
        private List<Object> snapshot(Set<Object> advertisements) {
            List<Object> result = new ArrayList<>(original);
            if (points != data || data.epoch != EPOCH.get()) return result;
            try {
                for (long target : data.targets) {
                    float fraction = (float) ((double) target / duration);
                    Object marker = markerConstructor.newInstance(Math.multiplyExact(target, 2000000L),
                            fraction, fraction, markerKind, "疑似广告结束", "");
                    result.add(marker);
                    if (advertisements != null) advertisements.add(marker);
                }
            } catch (Throwable error) { Log.d(TAG, "[ProgressMarkers] build marker failed: " + error); }
            return result;
        }
        @Override public Object get(int index) { return snapshot(null).get(index); }
        @Override public int size() { return snapshot(null).size(); }
        @Override public Iterator<Object> iterator() {
            Set<Object> ads = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            Iterator<Object> iterator = snapshot(ads).iterator();
            return new Iterator<Object>() {
                public boolean hasNext() { return iterator.hasNext(); }
                public Object next() {
                    Object value = iterator.next(); DrawState state = DRAW.get();
                    if (state != null) state.advertisement = ads.contains(value);
                    return value;
                }
            };
        }
    }

    private static void redraw() {
        MAIN.post(() -> {
            Activity current = activity.get();
            if (current != null && !current.isFinishing()) invalidate(current.getWindow().getDecorView(), 0);
        });
    }
    private static void invalidate(View view, int depth) {
        view.invalidate();
        if (depth < 24 && view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) invalidate(group.getChildAt(i), depth + 1);
        }
    }
}
