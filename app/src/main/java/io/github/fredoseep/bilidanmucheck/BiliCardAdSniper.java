package io.github.fredoseep.bilidanmucheck;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class BiliCardAdSniper {
    private static final String TAG = "SearchAndCardAdSniper";

    public static void hook(ClassLoader classLoader, DexKitHelper helper) {

        try{
            XposedHelpers.findAndHookMethod(
                    "com.bapis.bilibili.polymer.app.search.v1.SearchAllResponse",
                    classLoader,
                    "getItemList",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            List<?> original = (List<?>) param.getResult();
                            if (original == null) return;

                            List<Object> filtered = new ArrayList<>(original.size());
                            int removed = 0;

                            for (Object item : original) {
                                String gotoString =
                                        (String) XposedHelpers.getObjectField(item, "goto_");

                                if (gotoString != null
                                        && (gotoString.contains("video_ad")
                                        || gotoString.contains("picture_ad"))) {
                                    removed++;
                                } else {
                                    filtered.add(item);
                                }
                            }

                            if (removed > 0) {
                                param.setResult(filtered);
                                Log.d(TAG, "过滤广告 " + removed
                                        + " 条，剩余 " + filtered.size() + " 条");
                            }
                        }
                    }
            );

        } catch (Exception e) {
            Log.e(TAG,"Error1: "+e.getMessage());
        }



        try {
            XposedHelpers.findAndHookMethod(
                    helper.HOME_CARD_ADAPTER_CLASS_NAME,
                    classLoader,
                    helper.CARD_INFO_SETTING_METHOD_NAME,
                    "java.util.List",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            List<Object> modifiedList = (List<Object>) param.args[0];
                            if (modifiedList == null || modifiedList.isEmpty()) return;

                            Iterator<Object> iterator = modifiedList.iterator();
                            int removeCount = 0;

                            while (iterator.hasNext()) {
                                Object object = iterator.next();
                                if (object == null) continue;
//                                Log.d(TAG, (String) XposedHelpers.callMethod(object,"toString"));
                                try {
                                    String bannerType = (String) XposedHelpers.getObjectField(object, "a");
                                    if (bannerType != null && bannerType.contains("ad")) {
                                        iterator.remove();
                                        removeCount++;
                                        Log.i(TAG, "[BannerAd] 剔除首页Banner广告 |  " + XposedHelpers.callMethod(object,"toString"));
                                    }
                                } catch (Throwable t) {
                                    Log.w(TAG, "[BannerAd] 读取 Banner 字段异常: " + t.getMessage());
                                }
                            }

                            if (removeCount > 0) {
                                Log.i(TAG, "[BannerAd] 本次刷新共清理了 " + removeCount + " 个广告卡片");
                            }
                        }
                    }
            );
            Log.d(TAG, "[BannerAd] 首页 Banner Hook 注册成功");
        } catch (Throwable e) {
            Log.e(TAG, "[BannerAd] 首页 Banner Hook 失败: " + e.getMessage());
        }

        // ==========================================
        // Hook 3: UI 层级文本暴力追踪 (溯源专用)
        // ==========================================
//        try {
//            XposedHelpers.findAndHookMethod(
//                    "android.widget.TextView",
//                    classLoader,
//                    "setText",
//                    CharSequence.class,
//                    new XC_MethodHook() {
//                        @Override
//                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
//                            CharSequence text = (CharSequence) param.args[0];
//                            if (text != null) {
//                                String str = text.toString();
//                                // 只拦截高度可疑的广告文本，减少日志刷屏
//                                if (str.contains("微信") || str.contains("小程序") || str.contains("王者万象")||str.contains("乐高")||str.contains("可乐")) {
//                                    Log.w(TAG, "[Trace] 发现可疑广告文本渲染: [" + str + "]", new Throwable("Text Tracing Stack"));
//                                }
//                            }
//                        }
//                    }
//            );
//            Log.d(TAG, "[Trace] 文本追踪 Hook 注册成功");
//        } catch (Throwable e) {
//            Log.e(TAG, "[Trace] 文本追踪 Hook 失败: " + e.getMessage());
//        }

    }
}