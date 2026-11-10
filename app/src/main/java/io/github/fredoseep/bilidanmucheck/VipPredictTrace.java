package io.github.fredoseep.bilidanmucheck;// 放入你的模块源码目录，并在这里添加你自己的 package 声明。

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Bilibili 9.18.0：只观察，不修改参数、开关或返回值。
 * 入口：VipPredictTrace.hook(lpparam.classLoader);
 * 日志：Context.getFilesDir()/vip_predict_trace/<进程名>.log，8 MiB 轮换一份 .1。
 * trace ID 是协程上下文的尽力关联；最终 JS 回调另用 bridge 实例 + callbackId 精确匹配。
 * ENTER/EXIT 是单次 Java 调用；EXIT=COROUTINE_SUSPENDED 只表示挂起，不表示失败或最终结果。
 * PAGE 是异步在主线程读取的页面快照；若页面迅速跳转，可能读到跳转后的 URL。
 * 启动后应先看到 FILE_READY、HOOK_OK；完全没有 JSB_EXECUTE 通常说明尚未命中网页请求。
 * Java 8 源码，只依赖 Android SDK 和传统 Xposed API；无需编译依赖 Bilibili/Fastjson/Kotlin。
 */
public final class VipPredictTrace {
    private static final String TAG = "VipPredictTrace";
    private static final String VIP = "com.bilibili.tensorflow.model.mem.VipOpenMembershipPredictTFClient";
    private static final String OGV = "com.bilibili.tensorflow.model.mem.OgvOpenMembershipPredictTFClient";
    private static final String EXTRA = "VipPredictTrace.state";
    // 默认只记录每个时间窗的完整结果，避免逐事件日志影响原业务的 1 秒超时。
    private static final boolean TRACE_EACH_FEATURE = false;
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    private static final AtomicLong IDS = new AtomicLong();
    private static final AtomicLong LINES = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();
    private static final Map<Object, Trace> LINKS = Collections.synchronizedMap(new WeakHashMap<Object, Trace>());
    private static final Map<Object, Map<String, Trace>> CALLBACKS = new WeakHashMap<>();
    private static final Set<Member> DYNAMIC_HOOKS = new HashSet<>();
    private static final Set<Class<?>> LOGGER_CLASSES = new HashSet<>();
    private static final Set<ClassLoader> LOADERS = Collections.newSetFromMap(new WeakHashMap<ClassLoader, Boolean>());
    private static final ArrayDeque<String> EARLY = new ArrayDeque<>();
    private static final Object FILE_LOCK = new Object();
    private static volatile File logFile;
    private static volatile String processName = "unknown";
    private static volatile boolean attachHooked;
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(2048),
            runnable -> {
                Thread t = new Thread(runnable, "vip-predict-trace-writer");
                t.setDaemon(true);
                return t;
            }, (runnable, executor) -> DROPPED.incrementAndGet());

    private VipPredictTrace() {}

    public static void hook(ClassLoader classLoader) {
        synchronized (LOADERS) {
            if (!LOADERS.add(classLoader)) return;
        }
        installContext();
        event(null, "INSTALL_BEGIN", "loader=" + classLoader + " model=" + Build.MODEL
                + " Android=" + Build.VERSION.RELEASE + " SDK=" + Build.VERSION.SDK_INT);

        watch(classLoader, "com.bilibili.common.webview.js.JsbDynamicHandler", "invokeNative", "JSB_DISPATCH");
        watch(classLoader, "com.bilibili.tensorflow.jsb.TfLiteModelService", "execute", "JSB_EXECUTE");
        hookServiceConstructors(classLoader);
        watch(classLoader, VIP, "h", "VIP_ENTRY");
        watch(classLoader, VIP, "b", "VIP_TIMEOUT_WRAPPER");
        watch(classLoader, VIP, "a", "VIP_PREPARE_PREDICT");
        watch(classLoader, VIP, "g", "VIP_REMOTE_FEATURES");
        watch(classLoader, VIP, "e", "VIP_LOCAL_WINDOWS");
        watch(classLoader, VIP, "f", "VIP_LOCAL_WINDOW");
        if (TRACE_EACH_FEATURE) watch(classLoader, VIP, "c", "VIP_EVENT_SUM");
        watch(classLoader, VIP, "d", "VIP_INFERENCE");
        watch(classLoader, "Do0.a", "a", "CONFIG");
        watch(classLoader, "com.bilibili.tflite.TFLite", "prepare", "TF_SO_PREPARE");
        watch(classLoader, "com.bilibili.tflite.a", "a", "TF_SO_FILE");
        watch(classLoader, "com.bilibili.tensorflow.model.mem.c", "b", "MODEL_PREPARE");
        watch(classLoader, "com.bilibili.tensorflow.model.mem.c", "a", "TRANSFORM_PREPARE");
        watch(classLoader, "com.bilibili.tensorflow.data.mem.TfOpenMemberReportEventLoader", "b", "EVENT_MAP_PREPARE");
        if (TRACE_EACH_FEATURE) watch(classLoader, "com.bilibili.tensorflow.utils.b", "b", "FEATURE_BUCKET");
        // 另一条会员预测实现，避免只盯着 VIP.d 而漏掉 OGV.b。
        watch(classLoader, OGV, "i", "OGV_START");
        watch(classLoader, OGV, "b", "OGV_INFERENCE");
        watch(classLoader, "kntr.base.utils.foundation.RomUtils", "isHuaweiRom", "ROM_HUAWEI");
        watch(classLoader, "kntr.base.utils.foundation.RomUtils", "isHonorRom", "ROM_HONOR");
        watch(classLoader, "kntr.base.utils.foundation.RomUtils", "isHarmony", "ROM_HARMONY");

        String[] coroutines = {
                VIP + "$startOpenMembershipPredict$2",
                VIP + "$requestVipDataAndPredict$2",
                VIP + "$prepareModelAndPredict$remoteVipData$1",
                VIP + "$prepareModelAndPredict$tfByteBuffer$1",
                VIP + "$prepareModelAndPredict$1",
                VIP + "$requestVipDataAndPredict$1",
                VIP + "$processLastDaysUserFeatureLocalData$1",
                VIP + "$processUserFeatureLocalDataByTime$1",
                VIP + "$getOgvOpenMembershipEventCountByTimeAndEventId$1",
                "com.bilibili.tensorflow.jsb.TfLiteModelService$execute$1",
                "com.bilibili.common.webview.js.JsbDynamicHandler$invokeNative$1"
        };
        for (String name : coroutines) hookCoroutine(classLoader, name);
        hookBusinessErrors(classLoader);
        event(null, "INSTALL_END", "请检查 HOOK_OK / HOOK_MISSING / HOOK_FAIL；本类不主动发起预测");
    }

    private static final class Trace {
        final long id = IDS.incrementAndGet();
        final long started = SystemClock.elapsedRealtime();
        final String source;
        Trace(String source) { this.source = source; }
    }

    private static final class State {
        final Trace trace;
        final Trace previous;
        final long started = SystemClock.elapsedRealtime();
        State(Trace trace, Trace previous) { this.trace = trace; this.previous = previous; }
    }

    private static void watch(ClassLoader loader, String cls, String name, String stage) {
        try {
            Class<?> c = XposedHelpers.findClass(cls, loader);
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(c, name, observer(stage));
            event(null, hooks.isEmpty() ? "HOOK_MISSING" : "HOOK_OK", cls + "#" + name + " count=" + hooks.size());
        } catch (Throwable e) {
            event(null, "HOOK_FAIL", cls + "#" + name + " " + e);
        }
    }

    private static XC_MethodHook observer(final String stage) {
        return new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Trace previous = CURRENT.get();
                try {
                    Trace t = findTrace(p);
                    if ("JSB_DISPATCH".equals(stage)) {
                        if (p.args.length < 1 || !"globalVip.mobilePredict".equals(p.args[0])) return;
                        t = new Trace(stage);
                    } else if ("JSB_EXECUTE".equals(stage) || "VIP_ENTRY".equals(stage)
                            || "VIP_INFERENCE".equals(stage) || "OGV_START".equals(stage)
                            || "OGV_INFERENCE".equals(stage)) {
                        if (t == null) t = new Trace(stage);
                    } else if ("CONFIG".equals(stage)) {
                        if (p.args.length == 0 || !isConfigKey(p.args[0])) return;
                    } else if (t == null && !"TF_SO_PREPARE".equals(stage) && !"TF_SO_FILE".equals(stage)) {
                        return; // 全局方法仅在本追踪上下文记录，防止无关日志刷屏。
                    }
                    State state = new State(t, previous);
                    p.setObjectExtra(EXTRA, state);
                    setCurrent(t);
                    linkContinuationArgs(p.args, t);
                    String input;
                    if ("JSB_EXECUTE".equals(stage)) {
                        Object bridge = field(p.thisObject, "a");
                        hookCallbackClass(bridge);
                        String callback = String.valueOf(p.args[1]);
                        // 协程生成类恢复时会调用 execute(null, null, this)，不是新网页请求。
                        boolean resume = p.args[0] == null && p.args[1] == null;
                        if (!resume && p.args[1] != null) {
                            synchronized (CALLBACKS) {
                                Map<String, Trace> m = CALLBACKS.get(bridge);
                                if (m == null) { m = new HashMap<>(); CALLBACKS.put(bridge, m); }
                                if (m.size() > 128) m.clear();
                                m.put(callback, t);
                            }
                        }
                        input = "resume=" + resume + " data=" + value(p.args[0]) + " callbackId=" + callback
                                + " bridge=" + identity(bridge) + " continuation=" + continuationInfo(p.args);
                        if (!resume) capturePage(bridge, t);
                    } else if (stage.endsWith("INFERENCE")) {
                        ByteBuffer b = p.args.length > 1 && p.args[1] instanceof ByteBuffer ? (ByteBuffer) p.args[1] : null;
                        input = "features=" + value(p.args[0]) + " count="
                                + (p.args[0] instanceof List ? ((List<?>) p.args[0]).size() : "?")
                                + " modelBufferCapacity=" + (b == null ? "?" : b.capacity());
                    } else if ("VIP_REMOTE_FEATURES".equals(stage)) {
                        input = "remote=" + value(p.args[0]);
                    } else if ("CONFIG".equals(stage) || "FEATURE_BUCKET".equals(stage)
                            || "VIP_EVENT_SUM".equals(stage) || "VIP_LOCAL_WINDOW".equals(stage)
                            || "JSB_DISPATCH".equals(stage)) {
                        input = "args=" + value(p.args);
                    } else if (stage.startsWith("CO:")) {
                        input = "instance=" + identity(p.thisObject) + " label=" + field(p.thisObject, "label");
                    } else {
                        input = "continuation=" + continuationInfo(p.args);
                    }
                    event(t, stage + " ENTER", input);
                    if ("VIP_ENTRY".equals(stage) || stage.endsWith("INFERENCE")) {
                        event(t, stage + " STACK", Log.getStackTraceString(new Throwable("调用栈")));
                    }
                } catch (Throwable e) {
                    event(previous, "TRACE_ERROR", stage + " before " + e);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam p) {
                State s = (State) p.getObjectExtra(EXTRA);
                if (s == null) return;
                try {
                    String result = p.hasThrowable() ? "THROW " + Log.getStackTraceString(p.getThrowable()) : value(p.getResult());
                    event(s.trace, stage + " EXIT", "callMs=" + (SystemClock.elapsedRealtime() - s.started)
                            + " result=" + result);
                    if ("MODEL_PREPARE".equals(stage) || "TRANSFORM_PREPARE".equals(stage)) {
                        Class<?> c = p.method.getDeclaringClass();
                        event(s.trace, "MODEL_STATE", "model=" + fileInfo(field(c, "c"))
                                + " csv=" + fileInfo(field(c, "d")) + " version=" + value(field(c, "e"))
                                + " transformer=" + identity(field(c, "f")));
                    } else if ("EVENT_MAP_PREPARE".equals(stage)) {
                        Class<?> c = p.method.getDeclaringClass();
                        event(s.trace, "EVENT_MAP_STATE", "file=" + fileInfo(field(c, "c"))
                                + " map=" + value(field(c, "e")));
                    } else if ("TF_SO_PREPARE".equals(stage)) {
                        Object flow = call(p.thisObject, "getTfSoStateReadyFlow");
                        event(s.trace, "TF_SO_READY", "value=" + value(call(flow, "getValue")));
                    }
                } catch (Throwable e) {
                    event(s.trace, "TRACE_ERROR", stage + " after " + e);
                } finally {
                    setCurrent(s.previous);
                }
            }
        };
    }

    private static void hookCoroutine(ClassLoader loader, String name) {
        try {
            Class<?> c = XposedHelpers.findClass(name, loader);
            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Trace t = findTrace(p);
                        if (t != null) LINKS.put(p.thisObject, t);
                    } catch (Throwable ignored) {}
                }
            });
            String shortName = name.substring(name.lastIndexOf('.') + 1);
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(c, "invokeSuspend", observer("CO:" + shortName));
            event(null, hooks.isEmpty() ? "HOOK_MISSING" : "HOOK_OK", name + "#invokeSuspend count=" + hooks.size());
        } catch (Throwable e) { event(null, "HOOK_FAIL", name + " " + e); }
    }

    private static void hookServiceConstructors(ClassLoader loader) {
        try {
            Class<?> c = XposedHelpers.findClass("com.bilibili.tensorflow.jsb.TfLiteModelService", loader);
            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object bridge = field(p.thisObject, "a");
                        hookCallbackClass(bridge);
                        event(CURRENT.get(), "JSB_SERVICE_CREATED", "bridge=" + identity(bridge));
                    } catch (Throwable e) { event(CURRENT.get(), "TRACE_ERROR", "service constructor " + e); }
                }
            });
        } catch (Throwable e) { event(null, "HOOK_FAIL", "TfLiteModelService constructor " + e); }
    }

    private static void hookCallbackClass(Object bridge) {
        if (bridge == null) return;
        for (Class<?> c = bridge.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("callbackToJs") || m.getParameterTypes().length != 1
                        || m.getParameterTypes()[0] != Object[].class) continue;
                synchronized (DYNAMIC_HOOKS) {
                    if (!DYNAMIC_HOOKS.add(m)) continue;
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                try {
                                    Object[] args = (Object[]) p.args[0];
                                    if (args == null || args.length == 0) return;
                                    Trace t = null;
                                    synchronized (CALLBACKS) {
                                        Map<String, Trace> map = CALLBACKS.get(p.thisObject);
                                        if (map != null) t = map.remove(String.valueOf(args[0]));
                                    }
                                    if (t != null) event(t, "JSB_CALLBACK", "args=" + value(args));
                                } catch (Throwable e) { event(CURRENT.get(), "TRACE_ERROR", "callback " + e); }
                            }
                        });
                        event(null, "HOOK_OK", m.toString());
                    } catch (Throwable e) {
                        DYNAMIC_HOOKS.remove(m);
                        event(null, "HOOK_FAIL", m + " " + e);
                    }
                }
            }
        }
    }

    private static void hookBusinessErrors(ClassLoader loader) {
        try {
            Class<?> c = XposedHelpers.findClass("kntr.base.log.KLog_androidKt", loader);
            XposedBridge.hookAllMethods(c, "getKLog", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object logger = p.getResult();
                        if (logger == null) return;
                        synchronized (LOGGER_CLASSES) {
                            if (!LOGGER_CLASSES.add(logger.getClass())) return;
                        }
                        for (Class<?> k = logger.getClass(); k != null; k = k.getSuperclass()) {
                            for (Method m : k.getDeclaredMethods()) {
                                if (!m.getName().equals("e") || m.getParameterTypes().length < 2
                                        || m.getParameterTypes()[0] != String.class) continue;
                                synchronized (DYNAMIC_HOOKS) {
                                    if (!DYNAMIC_HOOKS.add(m)) continue;
                                    try {
                                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                                            @Override protected void beforeHookedMethod(MethodHookParam q) {
                                                try {
                                                    String tag = String.valueOf(q.args[0]);
                                                    if (tag.contains("VipOpenMembershipPredictTFClient")
                                                            || tag.contains("TFOpenMEModelLoader")
                                                            || tag.contains("TfOpenMemberReportEventLoader")) {
                                                        event(CURRENT.get(), "BUSINESS_ERROR", value(q.args));
                                                    }
                                                } catch (Throwable ignored) {}
                                            }
                                        });
                                    } catch (Throwable e) {
                                        DYNAMIC_HOOKS.remove(m);
                                        event(null, "HOOK_FAIL", "business error " + m + " " + e);
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });
            event(null, "HOOK_OK", "KLog error observer");
        } catch (Throwable e) { event(null, "HOOK_FAIL", "KLog error observer " + e); }
    }

    private static void capturePage(final Object bridge, final Trace trace) {
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Object web = call(bridge, "getJsbWeb");
                    Object host = call(bridge, "getHostContext");
                    event(trace, "PAGE", "url=" + value(call(web, "getUrl"))
                            + " originalUrl=" + value(call(web, "getOriginalUrl"))
                            + " host=" + (host == null ? "null" : host.getClass().getName())
                            + " web=" + identity(web));
                } catch (Throwable e) { event(trace, "PAGE_ERROR", String.valueOf(e)); }
            });
        } catch (Throwable e) { event(trace, "PAGE_ERROR", String.valueOf(e)); }
    }

    private static Trace findTrace(XC_MethodHook.MethodHookParam p) {
        Trace t = linked(p.thisObject);
        if (t != null) return t;
        if (p.args != null) for (int i = p.args.length - 1; i >= 0; --i) {
            t = linked(p.args[i]);
            if (t != null) return t;
        }
        return CURRENT.get();
    }

    private static Trace linked(Object o) {
        for (int i = 0; o != null && i < 24; i++) {
            Trace t = LINKS.get(o);
            if (t != null) return t;
            Object next = field(o, "completion");
            if (next == o) break;
            o = next;
        }
        return null;
    }

    private static void linkContinuationArgs(Object[] args, Trace t) {
        if (t == null || args == null) return;
        // 不关联第一个参数中的单例 client、模型或普通输入对象，避免不同请求串号。
        for (Object arg : args) {
            if (arg != null && isContinuation(arg.getClass())) LINKS.put(arg, t);
        }
    }

    private static boolean isContinuation(Class<?> c) {
        if (c == null) return false;
        if ("kotlin.coroutines.Continuation".equals(c.getName())) return true;
        for (Class<?> i : c.getInterfaces()) if (isContinuation(i)) return true;
        return isContinuation(c.getSuperclass());
    }

    private static String continuationInfo(Object[] args) {
        if (args != null) for (int i = args.length - 1; i >= 0; --i) {
            Object o = args[i];
            if (o != null && isContinuation(o.getClass())) return identity(o) + " label=" + field(o, "label");
        }
        return "none";
    }

    private static boolean isConfigKey(Object key) {
        return "tf_ogv_open_membership_switch".equals(key)
                || "tf_ogv_open_membership_huawei_switch_off".equals(key)
                || "tf_ogv_report_open_membership_switch".equals(key);
    }

    private static void setCurrent(Trace trace) {
        if (trace == null) CURRENT.remove(); else CURRENT.set(trace);
    }

    private static Object field(Object object, String name) {
        if (object == null) return null;
        boolean isStatic = object instanceof Class;
        Class<?> c = isStatic ? (Class<?>) object : object.getClass();
        for (; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(isStatic ? null : object);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object call(Object object, String name) {
        if (object == null) return null;
        try { return XposedHelpers.callMethod(object, name); }
        catch (Throwable e) { return "<" + name + " unavailable: " + e.getClass().getSimpleName() + ">"; }
    }

    private static String fileInfo(Object object) {
        if (!(object instanceof File)) return value(object);
        File f = (File) object;
        return f.getAbsolutePath() + " exists=" + f.exists() + " bytes=" + f.length();
    }

    private static String identity(Object o) {
        return o == null ? "null" : o.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(o));
    }

    private static String value(Object o) {
        try {
            if (o == null) return "null";
            if (o instanceof Throwable) return Log.getStackTraceString((Throwable) o);
            if (o.getClass().isArray()) {
                StringBuilder b = new StringBuilder("[");
                for (int i = 0; i < Array.getLength(o); i++) {
                    if (i > 0) b.append(", ");
                    b.append(value(Array.get(o, i)));
                    if (b.length() > 65536) { b.append("...<truncated>"); break; }
                }
                return b.append(']').toString();
            }
            String s = String.valueOf(o);
            return s.length() <= 65536 ? s : s.substring(0, 65536) + "...<truncated>";
        } catch (Throwable e) { return "<format-error " + e + ">"; }
    }

    private static void installContext() {
        try {
            Application app = AndroidAppHelper.currentApplication();
            if (app != null) initFile(app);
        } catch (Throwable ignored) {}
        synchronized (FILE_LOCK) {
            if (attachHooked) return;
            attachHooked = true;
        }
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try { initFile((Context) p.args[0]); }
                    catch (Throwable e) { Log.d(TAG, "FILE_INIT_FAIL " + e); }
                }
            });
        } catch (Throwable e) { event(null, "HOOK_FAIL", "Application.attach " + e); }
    }

    private static void initFile(Context context) {
        synchronized (FILE_LOCK) {
            if (logFile != null) return;
            try {
                processName = AndroidAppHelper.currentProcessName();
                if (processName == null) processName = context.getPackageName();
                File dir = new File(context.getFilesDir(), "vip_predict_trace");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("mkdir failed: " + dir);
                logFile = new File(dir, processName.replaceAll("[^a-zA-Z0-9._-]", "_") + ".log");
            } catch (Throwable e) { Log.d(TAG, "FILE_INIT_FAIL " + e); return; }
        }
        event(null, "FILE_READY", "path=" + logFile.getAbsolutePath() + " package=" + context.getPackageName());
    }

    private static void event(Trace trace, String stage, String content) {
        try {
            long seq = LINES.incrementAndGet();
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(new java.util.Date());
            String prefix = stamp + " seq=" + seq + " pid=" + Process.myPid() + " tid=" + Process.myTid()
                    + " thread=" + Thread.currentThread().getName() + " trace=" + (trace == null ? "-" : trace.id)
                    + (trace == null ? "" : " ageMs=" + (SystemClock.elapsedRealtime() - trace.started) + " source=" + trace.source)
                    + " [" + stage + "] ";
            String line = prefix + content.replace("\r", "\\r").replace("\n", "\\n");
            // Logcat 单条长度有限；每段保留完整前缀。文件中仍为一条完整记录。
            for (int i = 0; i < line.length(); i += 1800) {
                Log.d(TAG, (i == 0 ? "" : "seq=" + seq + " continuation ")
                        + line.substring(i, Math.min(i + 1800, line.length())));
            }
            WRITER.execute(() -> append(line));
        } catch (Throwable e) { Log.d(TAG, "TRACE_LOG_FAIL " + e); }
    }

    private static void append(String line) {
        synchronized (FILE_LOCK) {
            try {
                if (logFile == null) {
                    if (EARLY.size() >= 256) { EARLY.removeFirst(); DROPPED.incrementAndGet(); }
                    EARLY.addLast(line);
                    return;
                }
                if (logFile.length() >= MAX_FILE_BYTES) {
                    File old = new File(logFile.getAbsolutePath() + ".1");
                    if (old.exists() && !old.delete()) throw new IllegalStateException("delete failed: " + old);
                    if (!logFile.renameTo(old)) throw new IllegalStateException("rotate failed: " + logFile);
                }
                try (FileOutputStream out = new FileOutputStream(logFile, true)) {
                    while (!EARLY.isEmpty()) out.write((EARLY.removeFirst() + "\n").getBytes("UTF-8"));
                    long dropped = DROPPED.getAndSet(0);
                    if (dropped > 0) out.write(("TRACE_DROPPED count=" + dropped + "\n").getBytes("UTF-8"));
                    out.write((line + "\n").getBytes("UTF-8"));
                }
            } catch (Throwable e) { Log.d(TAG, "FILE_WRITE_FAIL " + e); }
        }
    }
}
