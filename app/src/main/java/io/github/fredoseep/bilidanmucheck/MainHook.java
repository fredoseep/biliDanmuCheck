package io.github.fredoseep.bilidanmucheck;

import android.app.AndroidAppHelper;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String PACKAGE_NAME = "tv.danmaku.bili";

    private static final String PACKAGE_NAME_ALIAS = "tv.danmaku.bilj";
    private static final String INTERNATIONAL_PACKAGE_NAME = "com.bilibili.app.in";
    private static final boolean IS_DEBUG = true;

    public static boolean isInternational = false;

    private int dynamicTargetViewId = -1;

    public static final ConcurrentHashMap<Long, String> GLOBAL_DANMAKU_DICT = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<String, Long> RECENT_MSGS = new ConcurrentHashMap<>();

    private static String LAST_FETCHED_CID = "";
    private static final Map<String, Integer> titleIndexMapping = new HashMap<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(PACKAGE_NAME) && !lpparam.packageName.equals(INTERNATIONAL_PACKAGE_NAME)&& !lpparam.packageName.equals(PACKAGE_NAME_ALIAS))
            return;
        if (lpparam.packageName.equals(INTERNATIONAL_PACKAGE_NAME)) isInternational = true;

        try {
            System.loadLibrary("dexkit");
        } catch (Throwable t) {
            log("加载 DexKit 动态库失败: " + t.getMessage());
        }

        DexKitHelper helper = new DexKitHelper();
        helper.resolve(lpparam);

        log("开始执行业务 Hook 逻辑...");
        executeOriginalHooks(lpparam, helper);
        BiliCardAdSniper.hook(lpparam.classLoader,helper);

        // ---- 新增：版本号检测逻辑 ----
        boolean shouldRunDescCopyFix = true;
        try {
            Object activityThread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread"
            );
            if (activityThread != null) {
                Context systemContext = (Context) XposedHelpers.callMethod(activityThread, "getSystemContext");
                PackageInfo packageInfo = systemContext.getPackageManager().getPackageInfo(lpparam.packageName, 0);
                int versionCode = packageInfo.versionCode;

                log("检测到宿主版本号: " + versionCode);

                if (versionCode <= 8090300) {
                    shouldRunDescCopyFix = false;
                }
            }
        } catch (Throwable t) {
            log("获取宿主版本号失败，将默认执行 descCopyFix: " + t.getMessage());
        }
        BiliAdSniper.hook(lpparam.classLoader);
        BiliSplashSniper.hook(lpparam.classLoader,helper);
        BiliMentionedSniper.hook(lpparam.classLoader,helper);
        BiliBottomNavSniper.hook(lpparam.classLoader);

        if (shouldRunDescCopyFix) {
            descCopyFix(lpparam, helper);
        } else {
            log("宿主版本号小于 8.9.0(8090300)，跳过 descCopyFix 逻辑。");
        }
    }

    private void descCopyFix(XC_LoadPackage.LoadPackageParam lpparam, DexKitHelper helper) {
        try {
            final String TAG = "BiliHook -> ";
            final WeakHashMap<View, GestureDetector> gestureDetectorMap = new WeakHashMap<>();
            XposedHelpers.findAndHookConstructor(
                    helper.DESCRIPTION_TEXTVIEW_CLASS_NAME,
                    lpparam.classLoader,
                    Context.class,
                    AttributeSet.class,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            android.widget.TextView tv = (android.widget.TextView) param.thisObject;
                            tv.setTextIsSelectable(true);
                            tv.setFocusable(true);
                            tv.setFocusableInTouchMode(true);
                            tv.setLongClickable(true);
                            tv.setMovementMethod(android.text.method.ArrowKeyMovementMethod.getInstance());
                        }
                    }
            );

            XposedHelpers.findAndHookMethod(
                    helper.DESCRIPTION_TEXTVIEW_CLASS_NAME,
                    lpparam.classLoader,
                    "onTouchEvent",
                    android.view.MotionEvent.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            final android.widget.TextView tv = (android.widget.TextView) param.thisObject;
                            android.view.MotionEvent event = (android.view.MotionEvent) param.args[0];
                            int action = event.getActionMasked();
                            if (action == android.view.MotionEvent.ACTION_DOWN) {
                                tv.requestFocus();
                                if (tv.getParent() != null) {
                                    tv.getParent().requestDisallowInterceptTouchEvent(true);
                                }
                            }

                            Object editorObj = null;
                            try {
                                java.lang.reflect.Field editorField = android.widget.TextView.class.getDeclaredField("mEditor");
                                editorField.setAccessible(true);
                                editorObj = editorField.get(tv);
                                if (editorObj != null) {
                                    java.lang.reflect.Method onTouchMethod = editorObj.getClass().getDeclaredMethod("onTouchEvent", android.view.MotionEvent.class);
                                    onTouchMethod.setAccessible(true);
                                    onTouchMethod.invoke(editorObj, event);
                                }
                            } catch (Exception ignored) {
                            }

                            final Object finalEditorObj = editorObj;

                            android.view.GestureDetector gd = gestureDetectorMap.get(tv);
                            if (gd == null) {
                                gd = new android.view.GestureDetector(tv.getContext(), new android.view.GestureDetector.SimpleOnGestureListener() {
                                    @Override
                                    public void onLongPress(android.view.MotionEvent e) {
                                        boolean result = tv.performLongClick();
                                        log(TAG + "performLongClick returned: " + result);

                                        if (!result && finalEditorObj != null) {
                                            try {
                                                int offset = tv.getOffsetForPosition(e.getX(), e.getY());
                                                int start = Math.max(0, offset - 2);
                                                int end = Math.min(tv.getText().length(), offset + 2);
                                                android.text.Selection.setSelection((android.text.Spannable) tv.getText(), start, end);

                                                java.lang.reflect.Method startActionMode = finalEditorObj.getClass().getDeclaredMethod("startSelectionActionModeAsync", boolean.class);
                                                startActionMode.setAccessible(true);
                                                startActionMode.invoke(finalEditorObj, false);
                                                log(TAG + "Hard launched Selection ActionMode.");
                                            } catch (Exception ex) {
                                                log(TAG + "Hard launch failed: " + ex.getMessage());
                                            }
                                        }
                                    }

                                    @Override
                                    public boolean onSingleTapUp(android.view.MotionEvent e) {
                                        tv.performClick();
                                        return true;
                                    }
                                });
                                gd.setIsLongpressEnabled(true);
                                gestureDetectorMap.put(tv, gd);
                            }

                            gd.onTouchEvent(event);

                            if (tv.getMovementMethod() != null && tv.getText() instanceof android.text.Spannable) {
                                tv.getMovementMethod().onTouchEvent(tv, (android.text.Spannable) tv.getText(), event);
                            }

                            return true;
                        }
                    }
            );
        } catch (Exception e) {
            log("BiliHook -> Error: " + e.toString());
        }
    }


    private void executeOriginalHooks(XC_LoadPackage.LoadPackageParam lpparam, DexKitHelper helper) {
        // 通用 RPC 数据分发处理
        XC_MethodHook universalRpcHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args == null || param.args.length < 3) return;

                Object payload = param.args[2];
                if (payload == null) return;

                String className = payload.getClass().getName();

                if (className.endsWith("EventReport$Request")) {
                    parseEventReport(payload);
                }
            }
        };

        if (isInternational) {
            try {
                Class<?> function6Class = XposedHelpers.findClass("kotlin.jvm.functions.Function6", lpparam.classLoader);
                Class<?> oClass = XposedHelpers.findClass(helper.INTERNATIONAL_CHRONOS_RPC_CLASS_NAME, lpparam.classLoader);

                XposedHelpers.findAndHookMethod(oClass, helper.INTERNATIONAL_INVOKE_METHOD_NAME, Class.class, function6Class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Object handlerImpl = param.args[1];
                        if (handlerImpl != null) {
                            XposedBridge.hookAllMethods(handlerImpl.getClass(), "invoke", universalRpcHook);
                        }
                    }
                });
            } catch (Throwable t) {
                log("bilibili.in fails to pin: " + t.toString());
            }
        } else {
            removeAdUnderPlayer(lpparam);
            try {
                BiliGsonLobotomy.hookPegasusAd(lpparam.classLoader, helper.PEGASUS_MODEL_CLASS_NAME);
            } catch (Throwable t) {}

            // 【新版本策略】：使用 DexKit 获取到的动态类名
            try {
                if (helper.CHRONOS_RPC_CLASS_NAME != null && !helper.CHRONOS_RPC_CLASS_NAME.isEmpty()) {
                    Class<?> targetClass = XposedHelpers.findClass(helper.CHRONOS_RPC_CLASS_NAME, lpparam.classLoader);
                    for (java.lang.reflect.Method method : targetClass.getDeclaredMethods()) {
                        if (method.getName().equals("invoke")) {
                            XposedBridge.hookMethod(method, universalRpcHook);
                            log("✅ Hook 新版 ChronosRpc 成功绑定类: " + helper.CHRONOS_RPC_CLASS_NAME);
                            break;
                        }
                    }
                }
            } catch (Throwable t) {
                log("Hook 新版 ChronosRpc 失败 (该版本可能不支持): " + t.getMessage());
            }

            // 【旧版本策略】：硬编码挂载经典的 ChronosMessageHandler
            try {
                String hardcodedTargetClass = "tv.danmaku.biliplayerv2.service.interact.biz.chronos.chronosrpc.ChronosMessageHandler";
                Class<?> targetClass = XposedHelpers.findClass(hardcodedTargetClass, lpparam.classLoader);
                XposedBridge.hookAllMethods(targetClass, "invoke", universalRpcHook);
                log("✅ Hook 旧版 ChronosRpc 成功硬编码绑定类: " + hardcodedTargetClass);
            } catch (Throwable t) {
                log("Hook 旧版 ChronosRpc 失败 (新版可能不存在此旧类): " + t.getMessage());
            }
        }
        hookClipboardToJump(lpparam);
    }


    private void parseEventReport(Object payload) {
        try {
            java.lang.reflect.Field extendsArgsField = payload.getClass().getDeclaredField("extendsArgs");
            extendsArgsField.setAccessible(true);
            Object extendsArgsObj = extendsArgsField.get(payload);

            if (extendsArgsObj instanceof Map) {
                Map<?, ?> argsMap = (Map<?, ?>) extendsArgsObj;
                String keyStr = String.valueOf(argsMap.get("key"));

                log("【RPC 事件流】侦测到 Key: " + keyStr);

                // 核心兼容点：同时放行 DmsegLoader(旧) 与 CommonDanmakuWorkFlows(新)
                if ((keyStr.contains("DmsegLoader") || keyStr.contains("CommonDanmakuWorkFlows")) && argsMap.containsKey("video_id")) {
                    String cid = String.valueOf(argsMap.get("video_id"));
                    log("【动作A】检测到弹幕加载，准备请求 cid: " + cid);
                    fetchDanmakuAsync(cid);
                } else if (argsMap.containsKey("dmid") && argsMap.containsKey("msg")) {
                    String dmidStr = String.valueOf(argsMap.get("dmid"));
                    String msg = String.valueOf(argsMap.get("msg"));
                    try {
                        RECENT_MSGS.put(msg, Long.parseLong(dmidStr));
                        log("【状态记录】存入近期点击记录: " + msg + " -> " + dmidStr);
                    } catch (Exception e) {
                    }
                }
            }
        } catch (Exception e) {
        }
    }

    private void hookClipboardToJump(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("android.content.ClipboardManager", lpparam.classLoader, "setPrimaryClip", ClipData.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    ClipData clipData = (ClipData) param.args[0];
                    if (clipData != null && clipData.getItemCount() > 0) {
                        final String copiedText = clipData.getItemAt(0).getText().toString();
                        log("【剪贴板】检测到复制动作，内容: " + copiedText);

                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                Long targetDmid = RECENT_MSGS.get(copiedText);
                                if (targetDmid != null) {
                                    log("复制内容匹配！准备解析 Hash 并跳转主页...");
                                    crackAndJump(String.valueOf(targetDmid));
                                    RECENT_MSGS.remove(copiedText);
                                }
                            }
                        }, 300);
                    }
                }
            });
        } catch (Exception e) {
            log("剪贴板 Hook 失败: " + e.getMessage());
        }
    }

    private void crackAndJump(String dmidStr) {
        try {
            long dmid = Long.parseLong(dmidStr);
            String hash = GLOBAL_DANMAKU_DICT.get(dmid);

            if (hash != null) {
                final List<String> candidates = BiliDanmuCrack.crack(hash);
                if (candidates.isEmpty()) {
                    log("error: The result is empty");
                    return;
                }

                log("获取到候选 UID 集合，启动后台线程校验: " + candidates.toString());

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        boolean found = false;
                        for (String realUid : candidates) {
                            if (isUIDValidSync(realUid)) {
                                log("API 校验通过，命中真实 UID: " + realUid);
                                found = true;

                                Context context = AndroidAppHelper.currentApplication();
                                if (context != null) {
                                    Intent intent = new Intent(Intent.ACTION_VIEW);
                                    intent.setData(Uri.parse("bilibili://space/" + realUid));
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    context.startActivity(intent);
                                }
                                break;
                            }
                        }

                        if (!found) {
                            log("候选 UID 均未通过在线 API 校验，可能是已被销号的用户。");
                        }
                    }
                }).start();

            } else {
                log("字典中未找到该 dmid: " + dmid + " (可能该弹幕不包含在基础历史池中)");
            }
        } catch (Exception e) {
            log("跳转报错: " + e.getMessage());
        }
    }

    private boolean isUIDValidSync(String UID) {
        try {
            String apiUrl = "https://api.bilibili.com/x/space/upstat?mid=" + UID;
            java.net.URL url = new java.net.URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);

            if (conn.getResponseCode() == 200) {
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder jsonResult = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonResult.append(line);
                }
                reader.close();

                org.json.JSONObject jsonObject = new org.json.JSONObject(jsonResult.toString());
                int code = jsonObject.optInt("code", -1);
                return code == 0;
            }
        } catch (Exception e) {
            log("❌ 网络 API 校验异常: " + e.getMessage());
        }
        return false;
    }

    private void fetchDanmakuAsync(final String cid) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String apiUrl = "https://comment.bilibili.com/" + cid + ".xml";
                    log("正在拉取弹幕 XML: " + apiUrl);
                    URL url = new java.net.URL(apiUrl);
                    HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Accept-Encoding", "gzip, deflate");
                    conn.setConnectTimeout(5000);

                    if (conn.getResponseCode() == 200) {
                        java.io.InputStream in = conn.getInputStream();
                        String encoding = conn.getContentEncoding();

                        if ("deflate".equalsIgnoreCase(encoding)) {
                            in = new java.util.zip.InflaterInputStream(in, new java.util.zip.Inflater(true));
                        } else if ("gzip".equalsIgnoreCase(encoding)) {
                            in = new java.util.zip.GZIPInputStream(in);
                        }

                        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"));
                        StringBuilder xmlResult = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            xmlResult.append(line);
                        }
                        reader.close();

                        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("p=\"([^\"]+)\"");
                        java.util.regex.Matcher matcher = pattern.matcher(xmlResult.toString());

                        int count = 0;
                        while (matcher.find()) {
                            String[] parts = matcher.group(1).split(",");
                            if (parts.length >= 8) {
                                try {
                                    long dmid = Long.parseLong(parts[7]);
                                    GLOBAL_DANMAKU_DICT.put(dmid, parts[6]);
                                    count++;
                                } catch (Exception ignored) {
                                }
                            }
                        }
                        log("✅ 【建账完毕】成功解压并缓存 " + count + " 条弹幕！");
                    }
                } catch (Exception e) {
                    log("❌ 网络请求或解析异常: " + e.getMessage());
                }
            }
        }).start();
    }

    private void removeAdUnderPlayer(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> ViewBindingsClass = XposedHelpers.findClass("androidx.viewbinding.ViewBindings", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(ViewBindingsClass, "findChildViewById", View.class, int.class, new XC_MethodHook() {

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (dynamicTargetViewId == -1) {
                        View rootView = (View) param.args[0];
                        if (rootView != null && rootView.getContext() != null) {
                            dynamicTargetViewId = rootView.getContext().getResources().getIdentifier("underplayer_container", "id", PACKAGE_NAME);

                            if (dynamicTargetViewId == 0) {
                                log("未能动态获取到控件 ID，请检查控件名是否正确");
                                dynamicTargetViewId = 0;
                            } else {
                                log("成功动态绑定控件 ID: " + dynamicTargetViewId);
                            }
                        }
                    }

                    if (dynamicTargetViewId > 0 && (int) param.args[1] == dynamicTargetViewId) {
                        View targetView = (View) param.getResult();
                        if (targetView != null) {
                            ViewGroup.LayoutParams params = targetView.getLayoutParams();
                            if (params != null) {
                                params.width = 0;
                                params.height = 0;
                                targetView.setLayoutParams(params);
                            }
                            targetView.setVisibility(View.GONE);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            log("error removing ad: " + t.toString());
        }

        try {
            Class<?> AdPausedPagePanelClass = XposedHelpers.findClass("com.bilibili.ad.adview.videodetail.pausedpage.AdPausedPagePanel", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(AdPausedPagePanelClass, "onCreateView", ViewGroup.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Context context = ((ViewGroup) param.args[0]).getContext();
                    param.setResult(new View(context));
                }
            });
        } catch (Throwable t) {
            log("error on paused page ad: " + t.toString());
        }

        try {
            Class<?> DetailAdServiceClass = XposedHelpers.findClass("com.bilibili.ship.theseus.ugc.ad.DetailAdService", lpparam.classLoader);
            for (Method method : DetailAdServiceClass.getDeclaredMethods()) {
                if (method.getName().equals("showPanel")) {
                    log("showPanel Method found");
                    XposedBridge.hookMethod(method, XC_MethodReplacement.DO_NOTHING);
                    break;
                }
            }
        } catch (Throwable t) {
            log("error on detail ad: " + t);
        }
    }

    public static void log(String msg) {
        if (IS_DEBUG) {
            XposedBridge.log("bilibili hook: " + msg);
        }
    }
}