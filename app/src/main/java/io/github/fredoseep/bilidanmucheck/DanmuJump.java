package io.github.fredoseep.bilidanmucheck;

import android.util.Log;

import java.util.Arrays;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class DanmuJump {
    private static final String TAG = "DanmuJump";
    // Temporary smoke test: select a video longer than one minute.
    private static final boolean TEST_AUTO_JUMP = false;
    private static final long TEST_TARGET_MS = 60_000L;
    private static final java.util.concurrent.atomic.AtomicBoolean testScheduled =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile java.lang.ref.WeakReference<Object> activeEngine =
            new java.lang.ref.WeakReference<>(null);

    private static final java.util.concurrent.atomic.AtomicBoolean scriptMismatchNotified =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 区分脚本版本不适配与普通文件读写失败。 */
    private static final class ScriptMismatchException extends java.io.IOException {
        ScriptMismatchException(String message) {
            super(message);
        }
    }

    private static void notifyScriptMismatch(android.content.Context context,
                                             ScriptMismatchException error) {
        Log.d(TAG, "[ChronosPatch] JS incompatible: " + error.getMessage());
        if (context == null || !scriptMismatchNotified.compareAndSet(false, true)) return;
        try {
            android.content.Context appContext = context.getApplicationContext();
            final android.content.Context toastContext = appContext != null ? appContext : context;
            boolean posted = new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    android.widget.Toast.makeText(toastContext,
                            "弹幕脚本版本不匹配，广告跳转和发送者查询暂不可用，请更新模块",
                            android.widget.Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    scriptMismatchNotified.set(false);
                    Log.d(TAG, "[ChronosPatch] mismatch toast failed: " + t.getClass().getName());
                }
            });
            if (!posted) scriptMismatchNotified.set(false);
        } catch (Throwable t) {
            scriptMismatchNotified.set(false);
            Log.d(TAG, "[ChronosPatch] mismatch toast scheduling failed: " + t.getClass().getName());
        }
    }

    private static volatile java.io.File voteLogDirectory;
    private static final java.util.concurrent.ExecutorService voteLogWriter =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "DanmuVoteLogWriter");
                thread.setDaemon(true);
                return thread;
            });

    /** 每次提示一条摘要，每张有效票一条记录；磁盘写入不占用播放器线程。 */
    private static void appendVoteLog(String jsonText) {
        final java.io.File directory = voteLogDirectory;
        final long receivedAt = System.currentTimeMillis();
        if (directory == null) {
            Log.d(TAG, "[DanmuVoteLog] directory not ready");
            return;
        }
        voteLogWriter.execute(() -> {
            try {
                org.json.JSONObject record = new org.json.JSONObject(jsonText);
                record.put("loggedAtMs", receivedAt);
                java.text.SimpleDateFormat timestamp = new java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss.SSS Z", java.util.Locale.ROOT);
                record.put("loggedAt", timestamp.format(new java.util.Date(receivedAt)));
                for (String key : new String[]{"appearanceMs", "targetMs", "currentMs"}) {
                    if (record.has(key) && !record.isNull(key)) {
                        record.put(key.replace("Ms", "Time"),
                                formatVideoTime(record.getLong(key)));
                    }
                }
                long sentAtSeconds = record.optLong("sentAtSeconds", 0L);
                if (sentAtSeconds > 0 && sentAtSeconds <= Long.MAX_VALUE / 1000L) {
                    record.put("sentAt", timestamp.format(new java.util.Date(sentAtSeconds * 1000L)));
                }
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new java.io.IOException("Cannot create vote log directory");
                }
                String day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
                        .format(new java.util.Date(receivedAt));
                java.io.File file = new java.io.File(directory, "votes-" + day + ".jsonl");
                byte[] bytes = (record.toString() + "\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(file, true)) {
                    output.write(bytes);
                }
            } catch (Throwable t) {
                Log.d(TAG, "[DanmuVoteLog] write failed: " + t.getClass().getName());
            }
        });
    }

    private static String formatVideoTime(long milliseconds) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d.%03d",
                milliseconds / 3600000L, (milliseconds / 60000L) % 60L,
                (milliseconds / 1000L) % 60L, milliseconds % 1000L);
    }

    public static void hook(ClassLoader classLoader) {
        installChronosProbe(classLoader);
//        try {
//            XposedHelpers.findAndHookMethod(
//                    "com.bilibili.ship.theseus.united.player.oldway.playercontainer."
//                            + "TheseusPlayerContainerProvider$providePlayerContainer$playerContainer$1$1$1",
//                    classLoader,
//                    "seekTo",
//                    int.class,
//                    boolean.class,
//                    new XC_MethodHook() {
//                        @Override
//                        protected void beforeHookedMethod(MethodHookParam param) {
//                            Log.d(TAG,
//                                    "[AirborneTrace] target=" + param.args[0]
//                                            + ", flag=" + param.args[1]
//                                            + ", thread=" + Thread.currentThread().getName()
//                            );
//                            Log.d(TAG, Arrays.toString(new Throwable("[AirborneTrace] seekTo caller").getStackTrace()));
//                        }
//                    }
//            );
//        } catch (Exception e) {
//            Log.d(TAG, "seekTo hook failed: " + e.getMessage());
//        }
        try{
//            Class<?> dispatcherClass = XposedHelpers.findClass(
//                    "tv.danmaku.biliplayerv2.service.interact.biz.chronos.chronosrpc.a",
//                    classLoader
//            );
//
//            XposedBridge.hookAllMethods(
//                    dispatcherClass,
//                    "invoke",
//                    new XC_MethodHook() {
//                        @Override
//                        protected void beforeHookedMethod(MethodHookParam param) {
//                            if (param.args.length != 6
//                                    || !(param.args[1] instanceof Class<?>)) {
//                                return;
//                            }
//
//                            Class<?> requestClass = (Class<?>) param.args[1];
//                            if (!requestClass.getName().endsWith(
//                                    "UpdatePlaybackStatus$Request")) {
//                                return;
//                            }
//
//                            try {
//                                Object context = param.args[0];
//                                Object sandbox = XposedHelpers.callMethod(
//                                        context, "getSandboxDir");
//                                String[] paths = (String[]) XposedHelpers.callMethod(
//                                        context, "getSearchPath");
//
//                                Log.d(TAG, "[ChronosPath] sandbox=" + sandbox);
//                                Log.d(TAG, "[ChronosPath] searchPaths="
//                                        + java.util.Arrays.toString(paths));
//                            } catch (Throwable t) {
//                                Log.d(TAG, "[ChronosPath] error\n"
//                                        + Log.getStackTraceString(t));
//                            }
//                        }
//                    }
//            );
//            Log.d(TAG, "[ChronosPath] hook installed");
            /////////////////////////////////////////////////////
            XposedHelpers.findAndHookMethod(
                    "com.bilibili.cron.ChronosPackage",
                    classLoader,
                    "createPackageFromFile",
                    android.content.Context.class,
                    java.io.File.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                java.io.File source = (java.io.File) param.args[1];
                                android.content.Context context =
                                        (android.content.Context) param.args[0];
                                if (voteLogDirectory == null && context != null) {
                                    voteLogDirectory = new java.io.File(context.getFilesDir(), "danmu_jump_logs");
                                    Log.d(TAG, "[DanmuVoteLog] directory=" + voteLogDirectory.getAbsolutePath());
                                }
                                java.io.File patched = buildTracePackage(context, source);
                                if (patched != null) {
                                    param.args[1] = patched;
                                    Log.d(TAG, "[ChronosPatch] redirected="
                                            + patched.getAbsolutePath());
                                }
                            } catch (ScriptMismatchException e) {
                                // JS 匹配失败：保持原始参数，原包继续正常加载。
                                notifyScriptMismatch((android.content.Context) param.args[0], e);
                            } catch (Throwable t) {
                                // No argument replacement on failure: load the original package.
                                Log.d(TAG, "[ChronosPatch] error\n"
                                        + Log.getStackTraceString(t));
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            java.io.File file = (java.io.File) param.args[1];

                            Log.d(TAG, "[ChronosPackage] source="
                                    + file.getAbsolutePath()
                                    + ", size=" + file.length());

                            if (param.hasThrowable()) {
                                Log.d(TAG, "[ChronosPackage] error\n"
                                        + Log.getStackTraceString(param.getThrowable()));
                                return;
                            }

                            Object pkg = param.getResult();
                            if (pkg == null) {
                                Log.d(TAG, "[ChronosPackage] result=null");
                                return;
                            }

                            try {
                                Log.d(TAG, "[ChronosPackage] info="
                                        + XposedHelpers.callMethod(pkg, "getInfo"));
                                Log.d(TAG, "[ChronosPackage] sandbox="
                                        + XposedHelpers.callMethod(
                                        pkg, "getSandBoxDirectory"));
                            } catch (Throwable t) {
                                Log.d(TAG, "[ChronosPackage] inspect error\n"
                                        + Log.getStackTraceString(t));
                            }
                        }
                    }
            );
            Log.d(TAG, "[ChronosPackage] hook installed");
        } catch (Exception e) {
            Log.d(TAG, "fail to hook method\n" + Log.getStackTraceString(e));
        }
    }

    /** Test JS bootstrap execution; does not seek or change the danmaku package. */
    private static void installChronosProbe(ClassLoader classLoader) {
        try {
            Class<?> engineClass = XposedHelpers.findClass(
                    "com.bilibili.cron.ChronosEngine", classLoader);
            Class<?> loggerInterface = XposedHelpers.findClass(
                    "com.bilibili.cron.ChronosPackageRunner$ExternalLogger",
                    classLoader);

            XposedBridge.hookAllConstructors(engineClass, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length != 4) {
                        return;
                    }
                    try {
                        final Object originalLogger = param.args[3];
                        final Object ownerEngine = param.thisObject;
                        Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                                loggerInterface.getClassLoader(),
                                new Class<?>[]{loggerInterface},
                                (proxyObject, method, args) -> {
                                    if ("log".equals(method.getName())
                                            && args != null && args.length == 1) {
                                        String message = String.valueOf(args[0]);
                                        int voteIndex = message.indexOf("[DanmuJumpVote]");
                                        if (voteIndex >= 0) {
                                            appendVoteLog(message.substring(
                                                    voteIndex + "[DanmuJumpVote]".length()));
                                        }
                                        int senderIndex = message.indexOf("[DanmuSenderData]");
                                        if (senderIndex >= 0) {
                                            DanmuSenderLookup.cacheClientDanmaku(message.substring(
                                                    senderIndex + "[DanmuSenderData]".length()));
                                        }
                                        int dataIndex = message.indexOf("[DanmuJumpData]");
                                        if (dataIndex >= 0) {
                                            relayCandidates(ownerEngine,
                                                    message.substring(dataIndex + "[DanmuJumpData]".length()));
                                        }
                                        if (message.contains("[DanmuJumpJS]")) {
                                            Log.d(TAG, "[ChronosJS] " + message);
                                            if (TEST_AUTO_JUMP && message.contains(
                                                    "[DanmuJumpJS] airborne controller ready")
                                                    && testScheduled.compareAndSet(false, true)) {
                                                final Object expectedEngine = activeEngine.get();
                                                new android.os.Handler(android.os.Looper.getMainLooper())
                                                        .postDelayed(() -> {
                                                            if (expectedEngine != null
                                                                    && activeEngine.get() == expectedEngine) {
                                                                jumpTo(TEST_TARGET_MS);
                                                            }
                                                        }, 8000L);
                                                Log.d(TAG, "[ChronosBridge] test scheduled in 8s, targetMs="
                                                        + TEST_TARGET_MS);
                                            }
                                        }
                                        if (originalLogger != null) {
                                            // Invoke the public interface method rather than a
                                            // potentially package-private implementation class.
                                            try {
                                                method.invoke(originalLogger, args);
                                            } catch (java.lang.reflect.InvocationTargetException e) {
                                                throw e.getCause();
                                            }
                                        }
                                        return null;
                                    }
                                    switch (method.getName()) {
                                        case "toString":
                                            return "DanmuJumpChronosLogger";
                                        case "hashCode":
                                            return System.identityHashCode(proxyObject);
                                        case "equals":
                                            return args != null && args.length == 1
                                                    && proxyObject == args[0];
                                        default:
                                            return null;
                                    }
                                });
                        // Native receives this logger during the constructor.
                        param.args[3] = proxy;
                        Log.d(TAG, "[ChronosProbe] logger attached");
                    } catch (Throwable t) {
                        Log.d(TAG, "[ChronosProbe] logger error\n"
                                + Log.getStackTraceString(t));
                    }
                }
            });

            XposedHelpers.findAndHookMethod(
                    engineClass, "runPackage",
                    "com.bilibili.cron.ChronosPackage",
                    String.class,
                    "com.bilibili.cron.ChronosPackage$LoadCompleteCallback",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object pkg = param.args[0];
                            if (pkg == null) {
                                return;
                            }
                            try {
                                String info = (String) XposedHelpers.callMethod(pkg, "getInfo");
                                if (info == null || !"danmaku-flame-master".equals(
                                        new org.json.JSONObject(info).optString("name"))) {
                                    return;
                                }
                                activeEngine = new java.lang.ref.WeakReference<>(param.thisObject);
                                String original = (String) param.args[1];
                                if (original == null || original.trim().isEmpty()
                                        || original.contains("[DanmuJumpJS]")) {
                                    return;
                                }
                                String expression = original.trim();
                                while (expression.endsWith(";")) {
                                    expression = expression.substring(
                                            0, expression.length() - 1).trim();
                                }
                                // Preserve the original expression's customData return value.
                                param.args[1] = "(function(){"
                                        + "var value=(" + expression + ");"
                                        + "try{cron.log('[DanmuJumpJS] bootstrap executed');}catch(e){}"
                                        + "return value;})();";
                                Log.d(TAG, "[ChronosProbe] bootstrap marker inserted");
                            } catch (Throwable t) {
                                Log.d(TAG, "[ChronosProbe] bootstrap error\n"
                                        + Log.getStackTraceString(t));
                            }
                        }
                    });
            Log.d(TAG, "[ChronosProbe] hooks installed");
        } catch (Throwable t) {
            Log.d(TAG, "[ChronosProbe] install error\n" + Log.getStackTraceString(t));
        }
    }

    private static final String DM_WORKER_SCRIPT =
            "res/src/danmaku/business/demand/entry_point/@cronc.worker.dfm_script.js";
    private static final String MAIN_SCRIPT =
            "res/src/danmaku/business/demand/entry_point/index_main.js";

    /** Builds a temporary trace-only copy. Never edits the original cache package. */
    private static synchronized java.io.File buildTracePackage(
            android.content.Context context, java.io.File source) throws Exception {
        if (source == null || !source.isFile()) {
            return null;
        }
        java.io.File output = null;
        boolean complete = false;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(source)) {
            java.util.zip.ZipEntry infoEntry = zip.getEntry("info.json");
            if (infoEntry == null) {
                return null;
            }
            String info;
            try (java.io.InputStream input = zip.getInputStream(infoEntry)) {
                info = new String(readLimited(input, 1024 * 1024),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            org.json.JSONObject metadata = new org.json.JSONObject(info);
            if (!"danmaku-flame-master".equals(metadata.optString("name"))) {
                return null;
            }
            org.json.JSONObject config = loadRules(context);
            java.util.zip.ZipEntry mainEntry = zip.getEntry(MAIN_SCRIPT);
            if (mainEntry == null) {
                throw new ScriptMismatchException("Main script is missing");
            }
            String script;
            try (java.io.InputStream input = zip.getInputStream(mainEntry)) {
                script = new String(readLimited(input, 4 * 1024 * 1024),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            String jumpAnchor = "const o=this._context.videoPlayer.currentTime;"
                    + "return this._context.videoPlayer.seekTo(s),"
                    + "this._airborneToast=new wS(this.size,this._suggestedFactor)";
            String undoAnchor = "this._airborneToast.onClick=a=>("
                    + "this._context.videoPlayer.seekTo(Math.floor(o)),!0)";
            if (countOccurrences(script, jumpAnchor) != 1
                    || countOccurrences(script, undoAnchor) != 1) {
                throw new ScriptMismatchException("Airborne anchors do not match this package version");
            }
            script = script.replace(jumpAnchor,
                    "const o=this._context.videoPlayer.currentTime;"
                            + "try{cron.log('[DanmuJumpJS] airborne targetSec='+s+' fromSec='+o);}catch(error){}"
                            + "return this._context.videoPlayer.seekTo(s),"
                            + "this._airborneToast=new wS(this.size,this._suggestedFactor)");
            script = script.replace(undoAnchor,
                    "this._airborneToast.onClick=a=>{"
                            + "try{cron.log('[DanmuJumpJS] undo targetSec='+Math.floor(o));}catch(error){}"
                            + "return this._context.videoPlayer.seekTo(Math.floor(o)),!0}");
            script = installJumpBridge(script);
            script = installAdPrompt(script, config);
            script = "try{cron.log('[DanmuJumpJS] main script entered sender-cache-v9');}catch(error){}\n"
                    + script;
            byte[] patchedScript = script.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.util.zip.ZipEntry workerEntry = zip.getEntry(DM_WORKER_SCRIPT);
            if (workerEntry == null) {
                throw new ScriptMismatchException("Danmaku worker script is missing");
            }
            byte[] patchedWorker;
            try (java.io.InputStream input = zip.getInputStream(workerEntry)) {
                String worker = new String(readLimited(input, 4 * 1024 * 1024),
                        java.nio.charset.StandardCharsets.UTF_8);
                patchedWorker = installCandidatePipeline(worker, config)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            java.io.File directory = new java.io.File(context.getCacheDir(), "danmu_jump_probe");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new java.io.IOException("Cannot create probe directory");
            }
            output = java.io.File.createTempFile("sender-cache-v9-", ".zip", directory);
            try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(
                    new java.io.BufferedOutputStream(new java.io.FileOutputStream(output)))) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                byte[] buffer = new byte[16384];
                while (entries.hasMoreElements()) {
                    java.util.zip.ZipEntry original = entries.nextElement();
                    java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(original.getName());
                    if (original.getTime() >= 0) {
                        entry.setTime(original.getTime());
                    }
                    out.putNextEntry(entry);
                    if (MAIN_SCRIPT.equals(original.getName())) {
                        out.write(patchedScript);
                    } else if (DM_WORKER_SCRIPT.equals(original.getName())) {
                        out.write(patchedWorker);
                    } else if (!original.isDirectory()) {
                        try (java.io.InputStream input = zip.getInputStream(original)) {
                            int length;
                            while ((length = input.read(buffer)) != -1) {
                                out.write(buffer, 0, length);
                            }
                        }
                    }
                    out.closeEntry();
                }
            }
            complete = true;
            Log.d(TAG, "[ChronosPatch] trace package ready, version="
                    + metadata.optString("version") + ", size=" + output.length());
            return output;
        } finally {
            if (!complete && output != null) {
                output.delete();
            }
        }
    }

    private static byte[] readLimited(java.io.InputStream input, int maximum)
            throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = input.read(buffer)) != -1) {
            if (length > maximum - out.size()) {
                throw new java.io.IOException("Script or metadata exceeds size limit");
            }
            out.write(buffer, 0, length);
        }
        return out.toByteArray();
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int position = 0;
        while ((position = text.indexOf(needle, position)) >= 0) {
            count++;
            position += needle.length();
        }
        return count;
    }

    /** Milliseconds at the module boundary; sent to the JS player as seconds. */
    public static void jumpTo(long targetMs) {
        if (targetMs < 0 || targetMs > Integer.MAX_VALUE) {
            Log.d(TAG, "[ChronosBridge] invalid targetMs=" + targetMs);
            return;
        }
        final Object engine = activeEngine.get();
        if (engine == null) {
            Log.d(TAG, "[ChronosBridge] no active engine");
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                if (activeEngine.get() != engine
                        || !Boolean.TRUE.equals(XposedHelpers.callMethod(engine, "isValid"))) {
                    Log.d(TAG, "[ChronosBridge] engine is no longer active");
                    return;
                }
                org.json.JSONObject args = new org.json.JSONObject();
                args.put("targetSec", targetMs / 1000.0);
                org.json.JSONObject request = new org.json.JSONObject();
                request.put("method", "DanmuJump_Jump");
                request.put("args", args);
                byte[] json = request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                // Chronos framing: type 0, big-endian int32 length, JSON bytes.
                byte[] message = java.nio.ByteBuffer.allocate(5 + json.length)
                        .order(java.nio.ByteOrder.BIG_ENDIAN)
                        .put((byte) 0).putInt(json.length).put(json).array();
                Class<?> callbackType = XposedHelpers.findClass(
                        "com.bilibili.cron.ChronosPackageRunner$MessageHandledCallback",
                        engine.getClass().getClassLoader());
                Object callback = java.lang.reflect.Proxy.newProxyInstance(
                        callbackType.getClassLoader(), new Class<?>[]{callbackType},
                        (proxy, method, values) -> {
                            if ("onComplete".equals(method.getName())) {
                                byte[] response = values == null ? null : (byte[]) values[0];
                                Log.d(TAG, "[ChronosBridge] response=" + decodeResponse(response));
                                return null;
                            }
                            switch (method.getName()) {
                                case "toString": return "DanmuJumpResponseCallback";
                                case "hashCode": return System.identityHashCode(proxy);
                                case "equals": return values != null && values.length == 1
                                        && proxy == values[0];
                                default: return null;
                            }
                        });
                XposedHelpers.callMethod(engine, "sendMessageAsync", message, callback);
                Log.d(TAG, "[ChronosBridge] sent targetMs=" + targetMs);
            } catch (Throwable t) {
                Log.d(TAG, "[ChronosBridge] send error\n" + Log.getStackTraceString(t));
            }
        });
    }

    private static String decodeResponse(byte[] response) {
        if (response == null) {
            return "null";
        }
        if (response.length >= 5 && response[0] == 0) {
            int length = java.nio.ByteBuffer.wrap(response, 1, 4)
                    .order(java.nio.ByteOrder.BIG_ENDIAN).getInt();
            if (length >= 0 && length <= response.length - 5) {
                return new String(response, 5, length, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return "unrecognized frame, length=" + response.length;
    }

    private static String installJumpBridge(String script) throws java.io.IOException {
        String constructor = "SS=class extends Gs{constructor(i){super(lt,ai,i,\"airborne\"),";
        String destroy = "destroy(i){var t;(t=this._airborneToast)==null||t.release(),"
                + "this._airborneToast=void 0,super.destroy(i)}}";
        String registration = "var Es=new J;";
        if (countOccurrences(script, constructor) != 1
                || countOccurrences(script, destroy) != 1
                || countOccurrences(script, registration) != 1) {
            throw new ScriptMismatchException("Bridge anchors do not match this package version");
        }
        // The main activity now creates this layouter proactively, using its own factory.
        script = script.replace(constructor, constructor
                + "globalThis.__danmuJumpAirborne=this,"
                + "cron.log('[DanmuJumpJS] airborne controller ready'),");
        script = script.replace(destroy,
                "destroy(i){if(globalThis.__danmuJumpAirborne===this)"
                        + "globalThis.__danmuJumpAirborne=void 0;var t;"
                        + "(t=this._airborneToast)==null||t.release(),"
                        + "this._airborneToast=void 0,super.destroy(i)}}");
        String bridge = "var Es=new J;"
                + "SS.prototype.__danmuJump=function(target){"
                + "const player=this._context.videoPlayer,from=player.currentTime;"
                + "if(this._airborneToast)this._airborneToast.release();"
                + "player.seekTo(target);"
                + "this._airborneToast=new wS(this.size,this._suggestedFactor);"
                + "this._airborneToast.onClick=()=>{"
                + "cron.log('[DanmuJumpJS] module undo targetSec='+Math.floor(from));"
                + "player.seekTo(Math.floor(from));return true;};"
                + "this.view.addSubView(this._airborneToast);this._airborneToast.show();"
                + "cron.log('[DanmuJumpJS] module jump targetSec='+target+' fromSec='+from);"
                + "return {accepted:true,targetSec:target,fromSec:from};};"
                + "Es.setMessageHandler('DanmuJump_Jump',request=>{"
                + "const target=request.args&&request.args.targetSec;"
                + "if(typeof target!=='number'||!Number.isFinite(target)||target<0)"
                + "return new pt({accepted:false,reason:'invalid target'});"
                + "const controller=globalThis.__danmuJumpAirborne;"
                + "if(!controller)return new pt({accepted:false,reason:'airborne controller unavailable'});"
                + "return new pt(controller.__danmuJump(target));});"
                + "cron.log('[DanmuJumpJS] jump bridge registered');";
        script = script.replace(registration, bridge);
        String activityAnchor = "const s=new Gy(t);t.push(s),Hd.init(t,s)";
        if (countOccurrences(script, activityAnchor) != 1) {
            throw new ScriptMismatchException("Main activity anchor does not match this version");
        }
        // fetchLayouter also applies size/configuration, tracks lifecycle and mounts the view.
        // Use the original factory rather than constructing an unattached SS instance.
        return script.replace(activityAnchor,
                "const s=new Gy(t);t.push(s),s.fetchLayouter(\"airborne\"),"
                        + "cron.log('[DanmuJumpJS] airborne controller proactively initialized'),"
                        + "Hd.init(t,s)");
    }

    /** Timestamp parser: excludes counter prefixes and targets below one minute. */
    private static String timeParserScript() {
        return "globalThis.__danmuJumpParseTimes=function(input){\n const digits={'零':0,'〇':0,'一':1,'二':2,'两':2,'三':3,'四':4,'五':5,'六':6,'七':7,'八':8,'九':9};\n function chinese(word){if(/[百千万亿]/.test(word))return word;if(!/[十百千]/.test(word))return word.split('').map(c=>digits[c]).join('');let value=0,last=0;for(const c of word){if(c in digits)last=digits[c];else{const unit=c==='十'?10:c==='百'?100:1000;value+=(last||1)*unit;last=0;}}return String(value+last);}\n const text=String(input).replace(/[０-９]/g,c=>String(c.charCodeAt(0)-65296)).replace(/：/g,':').replace(/[零〇一二两三四五六七八九十百千万亿]+/g,chinese),found=[],ranges=[];\n function add(start,end,seconds,format,explicit){ranges.push([start,end]);if(/[*xX]\\s*$/.test(text.slice(0,start))||/^\\s*[百千万亿年]/.test(text.slice(end)))return;const ms=seconds*1000;if(Number.isSafeInteger(ms)&&ms>=60000&&ms<=2147483647&&!found.some(r=>r.targetMs===ms))found.push({targetMs:ms,format:format,explicit:explicit});}\n let match;const clock=/\\d+(?:[:.]\\d+){1,2}/g;\n while((match=clock.exec(text))!==null){const token=match[0],parts=token.split(/[:.]/).map(Number),start=match.index,end=clock.lastIndex;ranges.push([start,end]);if((token.match(/\\.\\d+/g)||[]).some(part=>part.length!==3))continue;if(/[\\d:.]/.test(text[end]||'')||((text[start-1]===':'||text[start-1]==='.')&&/[\\d:.]/.test(text[start-2]||'')))continue;if(parts[parts.length-1]>=60||(parts.length===3&&parts[1]>=60))continue;add(start,end,parts.length===3?parts[0]*3600+parts[1]*60+parts[2]:parts[0]*60+parts[1],token.includes('.')?'dot':'clock',true);}\n const units=/(?:(\\d+)\\s*(?:小时|时)\\s*)?(?:(\\d+)\\s*(?:分钟|分)\\s*)?(?:(\\d+)\\s*(?:秒钟|秒))?/g;\n while((match=units.exec(text))!==null){if(!match[0]){units.lastIndex++;continue;}let end=units.lastIndex,seconds=Number(match[3]||0);if(match[2]&&!match[3]){const tail=/^\\s*(\\d+)(?!\\d)/.exec(text.slice(end));if(tail){seconds=Number(tail[1]);end+=tail[0].length;units.lastIndex=end;}}ranges.push([match.index,end]);if((match[1]&&Number(match[2]||0)>=60)||((match[1]||match[2])&&seconds>=60))continue;add(match.index,end,Number(match[1]||0)*3600+Number(match[2]||0)*60+seconds,'units',true);}\n const bare=/\\d+/g;while((match=bare.exec(text))!==null){const start=match.index,end=bare.lastIndex;if(ranges.some(r=>start<r[1]&&end>r[0])||/[:.]/.test(text[start-1]||'')||/[:.]/.test(text[end]||''))continue;const token=match[0];if(token.length===3||token.length===4){const seconds=Number(token.slice(-2));if(seconds<60)add(start,end,Number(token.slice(0,-2))*60+seconds,'compact',false);}}\n return found.slice(0,12);\n};\n";
    }

    private static org.json.JSONObject loadRules(android.content.Context context) throws Exception {
        java.io.File file = new java.io.File(context.getFilesDir(), "danmu_jump_rules.json");
        if (!file.exists()) {
            org.json.JSONObject initial = new org.json.JSONObject();
            initial.put("enabled", true);
            initial.put("minVotes", 3);
            initial.put("targetToleranceSeconds", 5);
            initial.put("sourceWindowSeconds", 30);
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(file)) {
                output.write(initial.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        org.json.JSONObject config;
        try (java.io.InputStream input = new java.io.FileInputStream(file)) {
            config = new org.json.JSONObject(new String(readLimited(input, 65536),
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        Log.d(TAG, "[DanmuRules] file=" + file.getAbsolutePath()
                + ", enabled=" + config.optBoolean("enabled", true)
                + ", minVotes=" + config.optInt("minVotes", 3));
        config.put("enabled", config.optBoolean("enabled", true));
        config.put("minVotes", Math.max(2, Math.min(20, config.optInt("minVotes", 3))));
        config.put("targetToleranceSeconds", Math.max(1, Math.min(10,
                config.optInt("targetToleranceSeconds", 5))));
        config.put("sourceWindowSeconds", Math.max(5, Math.min(120,
                config.optInt("sourceWindowSeconds", 30))));
        return config;
    }

    private static void relayCandidates(Object engine, String jsonText) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                if (!Boolean.TRUE.equals(XposedHelpers.callMethod(engine, "isValid"))) {
                    return;
                }
                org.json.JSONObject request = new org.json.JSONObject();
                request.put("method", "DanmuJump_AddCandidates");
                request.put("args", new org.json.JSONObject(jsonText));
                byte[] json = request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] frame = java.nio.ByteBuffer.allocate(5 + json.length)
                        .order(java.nio.ByteOrder.BIG_ENDIAN)
                        .put((byte) 0).putInt(json.length).put(json).array();
                Class<?> callbackClass = XposedHelpers.findClass(
                        "com.bilibili.cron.ChronosPackageRunner$MessageHandledCallback",
                        engine.getClass().getClassLoader());
                XposedHelpers.callMethod(engine, "sendMessageAsync",
                        new Class<?>[]{byte[].class, callbackClass}, frame, null);
            } catch (Throwable t) {
                Log.d(TAG, "[ChronosBridge] candidate relay error\n" + Log.getStackTraceString(t));
            }
        });
    }

    private static String installAdPrompt(String script, org.json.JSONObject config)
            throws java.io.IOException {
        String signature = "wS=(bs=class extends Tt{constructor(t,e){";
        String label = "this._label.text=F.strings.danmaku_global_string_89";
        String bind = "const s=new Gy(t);t.push(s),s.fetchLayouter(\"airborne\"),";
        String registration = "var Es=new J;";
        if (countOccurrences(script, signature) != 1 || countOccurrences(script, label) != 1
                || countOccurrences(script, bind) != 1 || countOccurrences(script, registration) != 1) {
            throw new ScriptMismatchException("Prompt anchors do not match this package version");
        }
        script = script.replace(signature, "wS=(bs=class extends Tt{constructor(t,e,moduleText){")
                .replace(label, "this._label.text=moduleText||F.strings.danmaku_global_string_89");
        script = script.replace(registration, registration
                + "const __DJ_CONFIG=" + config.toString() + ";" + adPromptScript());
        return script.replace(bind, bind + "__dj.bind(t,s),");
    }

    private static String installCandidatePipeline(String script, org.json.JSONObject config)
            throws java.io.IOException {
        String anchor = "h=x.DmSegMobileReply.fromBinary(ne.cronDataToUint8Array(u));let l=";
        if (countOccurrences(script, anchor) != 1) {
            throw new ScriptMismatchException("Danmaku decoder anchor does not match this version");
        }
        String prefix = timeParserScript() + "const __DJ_CONFIG=" + config.toString() + ";\n";
        return prefix + script.replace(anchor,
                "h=x.DmSegMobileReply.fromBinary(ne.cronDataToUint8Array(u));"
                        + candidateWorkerScript() + "let l=");
    }

    private static String adPromptScript() {
        return "const __dj={context:null,activity:null,previous:null,store:new Map(),prompt:null,logSequence:0,\n key(){const w=this.context&&this.context.workinfoManager.workInfo;return w?String(w.workId)+'/'+String(w.videoId):'';},\n bucket(key){let b=this.store.get(key);if(!b){b={rows:new Map(),targets:new Set(),blocks:[]};this.store.set(key,b);if(this.store.size>8)this.store.delete(this.store.keys().next().value);}return b;},\n close(){if(this.prompt&&!this.prompt.isReleased())this.prompt.release();this.prompt=null;},\n add(args){if(!args||!Array.isArray(args.rows))return;const b=this.bucket(String(args.workId)+'/'+String(args.videoId));for(const row of args.rows){if(Number.isFinite(row.appearanceMs)&&Number.isFinite(row.targetMs)&&row.targetMs>row.appearanceMs)b.rows.set(String(row.id)+'/'+row.targetMs,row);}cron.log('[DanmuJumpJS] candidates received count='+args.rows.length);},\n bind(context,activity){this.context=context;this.activity=activity;context.videoPlayer.addListener({onTimeUpdated:sec=>this.tick(sec),onSeeking:()=>{this.close();this.previous=null;},onSeeked:()=>{this.close();this.previous=null;}});context.workinfoManager.addListener({onWorkWillChange:()=>{this.close();this.previous=null;},onCurrentWorkChanged:()=>{this.close();this.previous=null;}});cron.log('[DanmuJumpJS] prompt pipeline ready');},\n audit(group,info,now,duration){try{const batchId=String(Date.now())+'-'+String(++this.logSequence)+'-'+this.key(),emit=record=>cron.log('[DanmuJumpVote]'+JSON.stringify(record));emit({event:'prompt',batchId:batchId,workId:String(info.workId),videoId:String(info.videoId),id:group.id,currentMs:now,durationMs:duration,targetMs:group.targetMs,votes:group.votes,explicitVotes:group.explicitVotes,minVotes:__DJ_CONFIG.minVotes||3,targetToleranceSeconds:__DJ_CONFIG.targetToleranceSeconds||5,sourceWindowSeconds:__DJ_CONFIG.sourceWindowSeconds||30});for(let index=0;index<group.voters.length;index++){const row=group.voters[index];emit({event:'vote',batchId:batchId,workId:String(info.workId),videoId:String(info.videoId),voteIndex:index+1,id:row.id,dmid:row.dmid,text:row.text,sentAtSeconds:row.sentAtSeconds,appearanceMs:row.appearanceMs,targetMs:row.targetMs,format:row.format,explicit:row.explicit});}}catch(error){cron.log('[DanmuJumpJS] vote audit error '+String(error));}},\n tick(sec){if(!__DJ_CONFIG.enabled||!Number.isFinite(sec))return;const now=sec*1000;if(this.previous!==null&&now>=this.previous&&now-this.previous<200)return;const previous=this.previous;this.previous=now;if(this.context.videoPlayer.playbackRate<=0)return;if(previous!==null&&(now<previous||now-previous>2000)){this.close();return;}if(this.prompt&&!this.prompt.isReleased())return;this.prompt=null;const info=this.context.workinfoManager.workInfo,duration=Number(info.duration),key=this.key(),b=this.store.get(key);if(!b||!Number.isFinite(duration)||duration<=0)return;\n const tolerance=(__DJ_CONFIG.targetToleranceSeconds||5)*1000,sourceWindow=(__DJ_CONFIG.sourceWindowSeconds||30)*1000,minVotes=__DJ_CONFIG.minVotes||3;\n const eligible=Array.from(b.rows.values()).filter(row=>row.targetMs<duration&&row.targetMs>now+1000&&!Array.from(b.targets).some(target=>Math.abs(target-row.targetMs)<=tolerance)&&!b.blocks.some(range=>row.appearanceMs>=range[0]&&row.appearanceMs<=range[1]));\n let best=null;for(const anchor of eligible){if(anchor.appearanceMs>now||anchor.appearanceMs<now-1000||(previous!==null&&anchor.appearanceMs<previous))continue;const votes=new Map();for(const row of eligible){if(Math.abs(row.targetMs-anchor.targetMs)>tolerance||Math.abs(row.appearanceMs-anchor.appearanceMs)>sourceWindow)continue;const old=votes.get(row.id);if(!old||(!old.explicit&&row.explicit))votes.set(row.id,row);}if(votes.size<minVotes)continue;const members=Array.from(votes.values()),values=members.map(row=>row.targetMs).sort((a,b)=>a-b),target=values[Math.floor(values.length/2)],agree=members.filter(row=>Math.abs(row.targetMs-target)<=tolerance),explicitVotes=agree.filter(row=>row.explicit).length;if(agree.length<minVotes)continue;\n const group={id:anchor.id,appearanceMs:anchor.appearanceMs,targetMs:target,votes:agree.length,explicitVotes:explicitVotes,voters:agree};if(!best||group.votes>best.votes||(group.votes===best.votes&&group.explicitVotes>best.explicitVotes))best=group;}if(!best)return;\n const controller=this.activity.fetchLayouter('airborne');if(!controller)return;if(controller._airborneToast&&!controller._airborneToast.isReleased())return;\n b.targets.add(best.targetMs);const prompt=new wS(controller.size,controller._suggestedFactor,'检测到广告，点击跳转');this.prompt=prompt;controller._airborneToast=prompt;\n prompt.onClick=()=>{this.prompt=null;const current=this.context.videoPlayer.currentTime*1000,newDuration=Number(this.context.workinfoManager.workInfo.duration);if(this.key()!==key||best.targetMs<=current+1000||!Number.isFinite(newDuration)||best.targetMs>=newDuration){cron.log('[DanmuJumpJS] prompt click rejected: context or target changed');return true;}b.blocks.push([Math.max(0,current-1000),best.targetMs]);controller.__danmuJump(best.targetMs/1000);return true;};controller.view.addSubView(prompt);prompt.show();this.audit(best,info,now,duration);cron.log('[DanmuJumpJS] prompt shown '+JSON.stringify({videoId:String(info.videoId),id:best.id,appearanceMs:best.appearanceMs,targetMs:best.targetMs,currentMs:now,durationMs:duration,votes:best.votes,explicitVotes:best.explicitVotes}));}\n};\nEs.setMessageHandler('DanmuJump_AddCandidates',request=>{__dj.add(request.args);return new pt({accepted:true});});\n";
    }

    private static String candidateWorkerScript() {
        return "try{const senders=[];for(const row of h.elems||[]){const id=String(row.idStr||row.id||''),hash=String(row.midHash||'');if(!id||!/^[0-9a-f]{1,8}$/i.test(hash))continue;senders.push({id:id,hash:hash});if(senders.length>=128)cron.log('[DanmuSenderData]'+JSON.stringify({videoId:String(i),rows:senders.splice(0)}));}if(senders.length)cron.log('[DanmuSenderData]'+JSON.stringify({videoId:String(i),rows:senders}));cron.log('[DanmuJumpJS] sender segment cached videoId='+String(i)+' total='+(h.elems||[]).length);}catch(error){cron.log('[DanmuJumpJS] sender cache error '+String(error));}\ntry{\n const rows=h.elems||[],selected=[];let numeric=0,accepted=0;\n if(__DJ_CONFIG.enabled)for(const row of rows){const text=String(row.content||''),times=globalThis.__danmuJumpParseTimes(text),appearance=Number(row.progress);if(times.length)numeric++;if(!Number.isFinite(appearance)||appearance<0)continue;for(const parsed of times){if(parsed.targetMs<=appearance)continue;accepted++;selected.push({id:String(row.idStr||row.id||'')+'@'+appearance,dmid:String(row.idStr||row.id||''),text:text,sentAtSeconds:Number(row.ctime)||null,appearanceMs:appearance,targetMs:parsed.targetMs,explicit:parsed.explicit,format:parsed.format});if(selected.length>=100)cron.log('[DanmuJumpData]'+JSON.stringify({workId:String(s),videoId:String(i),rows:selected.splice(0)}));}}\n if(selected.length)cron.log('[DanmuJumpData]'+JSON.stringify({workId:String(s),videoId:String(i),rows:selected}));\n cron.log('[DanmuJumpJS] segment '+JSON.stringify({workId:String(s),videoId:String(i),segmentIndex:t.index,total:rows.length,numericDanmaku:numeric,forwardCandidates:accepted}));\n}catch(error){cron.log('[DanmuJumpJS] candidate pipeline error '+String(error));}\n";
    }
}
