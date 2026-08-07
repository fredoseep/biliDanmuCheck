package com.fredoseep.biliroaming;

import android.app.AndroidAppHelper;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String packageName = "tv.danmaku.bili";
    private static final String albumRecycleViewHolderClassName = "PJ0.e";
    private static final String publishArchiveCollectionFieldName = "a";
    private static final String albumSelectPageViewClassName = "OH0.X";
    private static final String RECYCLER_VIEW_FIELD_NAME = "f";

    private static final int UNDERPLAYER_CONTAINER_RID = 0x7f095065;

    public static final ConcurrentHashMap<Long, String> GLOBAL_DANMAKU_DICT = new ConcurrentHashMap<>();

    public static final ConcurrentHashMap<String, Long> RECENT_MSGS = new ConcurrentHashMap<>();

    private static final boolean IS_DEBUG = false;
    private static final Map<String, Integer> titleIndexMapping = new HashMap<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(packageName)) return;

        try {
            Class<?> AlbumRecycleViewClass = XposedHelpers.findClass(albumRecycleViewHolderClassName, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(AlbumRecycleViewClass, "onBindViewHolder", "androidx.recyclerview.widget.RecyclerView$ViewHolder", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    List<?> publishArchiveCollectionList = (List<?>) XposedHelpers.getObjectField(param.thisObject, publishArchiveCollectionFieldName);
                    if (publishArchiveCollectionList != null && !publishArchiveCollectionList.isEmpty()) {
                        int index = 0;
                        for (Object publishArchiveCollection : publishArchiveCollectionList) {
                            String currentTitle = XposedHelpers.callMethod(publishArchiveCollection, "getTitle").toString().toLowerCase();
                            titleIndexMapping.put(currentTitle, index);
                            index++;
                        }
                    }
                }
            });
        } catch (Throwable t) {
            log("❌ error in onBindViewHolder: " + t.toString());
        }

        try {
            Class<?> AlbumSelectViewClass = XposedHelpers.findClass(albumSelectPageViewClassName, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(AlbumSelectViewClass, "inflate", LayoutInflater.class, ViewGroup.class, boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    final Object popUpView = param.getResult();
                    if (popUpView == null) return;

                    final ViewGroup originalViewGroup = (ViewGroup) XposedHelpers.callMethod(popUpView, "getRoot");
                    final Context context = originalViewGroup.getContext();

                    int oldTitleTextViewId = context.getResources().getIdentifier("collection_dialog_title_view", "id", packageName);
                    View oldTitleTextView = originalViewGroup.findViewById(oldTitleTextViewId);

                    if (oldTitleTextView == null) return;

                    ColorStateList originalColor = ((TextView) oldTitleTextView).getTextColors();
                    ViewGroup oldTitleTextViewParent = (ViewGroup) oldTitleTextView.getParent();
                    int index = oldTitleTextViewParent.indexOfChild(oldTitleTextView);
                    ViewGroup.LayoutParams params = oldTitleTextView.getLayoutParams();

                    oldTitleTextViewParent.removeView(oldTitleTextView);

                    final EditText searchbar = new EditText(context);
                    searchbar.setHint("搜索合集...");
                    searchbar.setHintTextColor(Color.GRAY);
                    searchbar.setTextColor(originalColor);
                    searchbar.setLayoutParams(params);
                    searchbar.setId(oldTitleTextViewId);
                    searchbar.setBackgroundColor(Color.TRANSPARENT);

                    searchbar.addTextChangedListener(new TextWatcher() {
                        @Override
                        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

                        @Override
                        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

                        @Override
                        public void afterTextChanged(Editable editable) {
                            String input = editable.toString().toLowerCase().trim();
                            if (input.isEmpty() || titleIndexMapping.isEmpty()) return;

                            final Object recyclerViewObj;
                            try {
                                recyclerViewObj = XposedHelpers.getObjectField(popUpView, RECYCLER_VIEW_FIELD_NAME);
                            } catch (Throwable t) {
                                return;
                            }

                            if (recyclerViewObj == null) return;

                            int targetIndex = -1;
                            for (Map.Entry<String, Integer> entry : titleIndexMapping.entrySet()) {
                                if (entry.getKey().contains(input)) {
                                    targetIndex = entry.getValue();
                                    break;
                                }
                            }

                            if (targetIndex != -1) {
                                final int finalTargetIndex = targetIndex;
                                searchbar.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            XposedHelpers.callMethod(recyclerViewObj, "scrollToPosition", finalTargetIndex);
                                        } catch (Throwable t) {
                                            log("❌ 滚动时发生异常: " + t.toString());
                                        }
                                    }
                                });
                            }
                        }
                    });

                    oldTitleTextViewParent.addView(searchbar, index);
                }
            });
        } catch (Throwable t) {
            log("❌ error at replace title: " + t.toString());
        }

        removeAdUnderPlayer(lpparam);


        try {
            Class<?> targetClass = XposedHelpers.findClass("tv.danmaku.biliplayerv2.service.interact.biz.chronos.chronosrpc.a", lpparam.classLoader);
            for (java.lang.reflect.Method method : targetClass.getDeclaredMethods()) {
                if (method.getName().equals("invoke")) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
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
                    });
                    break;
                }
            }
        } catch (Throwable t) {
            log("❌ error hooking chronosrpc invoke: " + t.toString());
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

                if (keyStr.contains("DmsegLoader") && argsMap.containsKey("video_id")) {
                    String cid = String.valueOf(argsMap.get("video_id"));
                    log("【动作A】检测到弹幕加载，准备请求 cid: " + cid);
                    fetchDanmakuAsync(cid);
                }
                else if (argsMap.containsKey("dmid") && argsMap.containsKey("msg")) {
                    String dmidStr = String.valueOf(argsMap.get("dmid"));
                    String msg = String.valueOf(argsMap.get("msg"));
                    try {
                        RECENT_MSGS.put(msg, Long.parseLong(dmidStr));
                        log("【状态记录】存入近期点击记录: " + msg + " -> " + dmidStr);
                    } catch (Exception e) {}
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
                                    log("🎯 复制内容匹配！准备解析 Hash 并跳转主页...");
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
                String realUid = BiliDanmuCrack.crack(hash);
                log("破译成功！真实 UID: " + realUid);

                Context context = AndroidAppHelper.currentApplication();
                if (context != null) {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse("bilibili://space/" + realUid));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                }
            } else {
                log("字典中未找到该 dmid: " + dmid + " (可能该弹幕不包含在基础历史池中)");
            }
        } catch (Exception e) {
            log("跳转报错: " + e.getMessage());
        }
    }

    private void fetchDanmakuAsync(final String cid) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String apiUrl = "https://comment.bilibili.com/" + cid + ".xml";
                    log("正在拉取弹幕 XML: " + apiUrl);
                    java.net.URL url = new java.net.URL(apiUrl);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
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
                                } catch (Exception ignored) {}
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
                    if ((int) param.args[1] == UNDERPLAYER_CONTAINER_RID) {
                        View targetView = (View) param.getResult();
                        if (targetView != null) {
                            ViewGroup.LayoutParams params = targetView.getLayoutParams();
                            if (params != null) {
                                params.width = 0;
                                params.height = 0;
                                targetView.setLayoutParams(params);
                            }
                            targetView.setVisibility(View.GONE);
                            if (targetView.getParent() instanceof View) {
                                View parentRowContainer = (View) targetView.getParent();
                                parentRowContainer.setVisibility(View.GONE);
                            }
                        }
                    }
                }
            });
        } catch (Throwable t) {
            log("error: " + t.toString());
        }
    }

    private void log(String msg) {
        if (IS_DEBUG) {
            XposedBridge.log("bilibili hook: " + msg);
        }
    }
}