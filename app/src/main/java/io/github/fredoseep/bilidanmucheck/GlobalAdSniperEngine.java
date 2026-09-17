package io.github.fredoseep.bilidanmucheck;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class GlobalAdSniperEngine {

    private static final String TAG = "GlobalAdSniperEngine";


    /**
     * 测试开关：true 时把识别到广告的原始响应追加写入应用外部私有目录
     */
    public static final boolean IS_TESTING = BuildConfig.DEBUG;

    /**
     * 是否关闭主页自动刷新（切后台再回来不自动刷新）
     */
    public static final boolean DISABLE_AUTO_REFRESH = true;

    /**
     * 是否开启自定义主页列数
     */
    public static final boolean ENABLE_CUSTOM_COLUMN = true;
    /**
     * 自定义主页列数（默认通常是 2，改成 1 为单列大图卡片）
     */
    public static final int CUSTOM_COLUMN_COUNT = 2;

    // ====================================================

    private static final long BODY_REQUEST_BYTES = 4L * 1024 * 1024;
    private static final char BACKSLASH = '\\';
    private static final String WS = " \t\r\n";

    private static Class<?> sResponseBodyCls;
    private static Class<?> sMediaTypeCls;
    private static Method sCreateMethod;
    private static Method sContentTypeMethod;

    public static void hook(ClassLoader classLoader,DexKitHelper helper) {

        try {
            XposedHelpers.findAndHookMethod(
                    "com.bilibili.pegasus.request.PegasusGsonParser",
                    classLoader,
                    helper.PEGASUS_SUS_GSON_PARSER_PARSER_METHOD_NAME,
                    "okhttp3.ResponseBody",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Object responseBody = param.args[0];
                            if (responseBody == null) return;

                            try {
                                // 1. 读出原始 JSON 文本
                                String json = readBodyText(responseBody);
                                if (json == null || json.length() < 64) return;

                                // 2. 修改云控配置（禁止刷新、自定义列数）
                                String configModifiedJson = modifyCloudConfig(json);
                                boolean isConfigModified = !configModifiedJson.equals(json);
                                json = configModifiedJson;

                                // 3. 快速预判，如果不含广告且也没改云控，直接放行走原逻辑
                                boolean hasAd = mayContainAd(json);
                                if (!hasAd && !isConfigModified) {
                                    return;
                                }

                                // 4. 括号配平切除广告卡片
                                String cleaned = json;
                                if (hasAd) {
                                    String noAdJson = removeAdItems(json);
                                    if (noAdJson != null && noAdJson.length() < json.length()) {
                                        cleaned = noAdJson;
                                        Log.d(TAG, "[AdFilter] 响应已净化广告: " + json.length() + " -> " + cleaned.length() + " 字符");

                                        if (IS_TESTING) saveJsonToFile(json);
                                    }
                                }

                                // 如果文本完全没变，直接返回
                                if (cleaned.equals(json) && !isConfigModified) return;

                                // 5. 构造修改后的 ResponseBody 并替换入参
                                Object newBody = buildResponseBody(responseBody, cleaned);
                                if (newBody == null) return;
                                param.args[0] = newBody;

                            } catch (Throwable t) {
                                Log.e(TAG, "净化或修改响应失败，已回退", t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object result = param.getResult();
                            if (result == null) return;
                            if (result.getClass().getName().contains("ResponseBody")) return;
                            cleanResponseData(result);
                        }
                    }
            );
        } catch (Throwable e) {
            Log.e(TAG, "Hook PegasusGsonParser.g 失败: " + e.getMessage());
        }

        try {
            Class<?> gsonClass = XposedHelpers.findClass("com.google.gson.Gson", classLoader);

            de.robv.android.xposed.XposedBridge.hookAllMethods(gsonClass, "fromJson", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args == null || param.args.length < 2) return;

                    Object inputArg = param.args[0];
                    Object typeArg = param.args[1];

                    if (typeArg == null) return;
                    String typeName = typeArg.toString();

                    // 只狙击目标类型包含 SplashListResponse 的反序列化请求
                    if (typeName.contains("SplashListResponse")) {
                        String jsonString = null;
                        boolean isReader = false;

                        // 1. 提取原始 JSON 字符串
                        if (inputArg instanceof String) {
                            jsonString = (String) inputArg;
                        } else if (inputArg instanceof java.io.Reader) {
                            isReader = true;
                            java.io.Reader reader = (java.io.Reader) inputArg;

                            // 注意：读取 Reader 会消耗掉流，必须完整读出
                            StringBuilder sb = new StringBuilder();
                            char[] buffer = new char[4096];
                            int charsRead;
                            while ((charsRead = reader.read(buffer)) != -1) {
                                sb.append(buffer, 0, charsRead);
                            }
                            jsonString = sb.toString();
                        }

                        if (jsonString != null && jsonString.contains("\"list\"")) {
                            try {
                                // 2. 使用自带的 JSONObject 强行清空广告数组
                                org.json.JSONObject jsonObj = new org.json.JSONObject(jsonString);
                                if (jsonObj.has("data")) {
                                    org.json.JSONObject dataObj = jsonObj.getJSONObject("data");
                                    if (dataObj.has("list")) {
                                        org.json.JSONArray listArray = dataObj.getJSONArray("list");
                                        int count = listArray.length();

                                        // 釜底抽薪：直接放一个空的 JSON 数组进去，秒杀所有开屏广告
                                        dataObj.put("list", new org.json.JSONArray());

                                        String newJson = jsonObj.toString();

                                        // 3. 将洗净的 JSON 替换回入参
                                        if (isReader) {
                                            param.args[0] = new java.io.StringReader(newJson);
                                        } else {
                                            param.args[0] = newJson;
                                        }

                                        android.util.Log.d(TAG, "[SplashFilter] 🚀 成功通过文本拦截，清空了 " + count + " 个开屏/闪屏广告！");
                                    }
                                }
                            } catch (Exception e) {
                                // 容错：如果 JSON 解析失败，必须把原样读出来的字符串塞回去，否则流断裂会导致闪退
                                if (isReader) {
                                    param.args[0] = new java.io.StringReader(jsonString);
                                }
                                android.util.Log.e(TAG, "解析开屏广告 JSON 失败", e);
                            }
                        } else if (isReader && jsonString != null) {
                            // 如果没匹配到，也必须把流还回去
                            param.args[0] = new java.io.StringReader(jsonString);
                        }
                    }
                }
            });
            android.util.Log.d(TAG, "开屏广告文本级拦截器 (Gson.fromJson) 挂载成功！");
        } catch (Throwable t) {
            android.util.Log.e(TAG, "Hook Gson.fromJson 拦截开屏广告失败", t);
        }
    }

    // ==================== 云控修改逻辑 ====================

    /**
     * 利用正则替换修改 B 站下发的 config 字段
     */
    private static String modifyCloudConfig(String json) {
        String result = json;

        if (DISABLE_AUTO_REFRESH) {
            // 将各类刷新超时时间设置为 99999999 秒（约 3 年不刷新）
            result = result.replaceAll("\"auto_refresh_time\"\\s*:\\s*\\d+", "\"auto_refresh_time\":99999999");
            result = result.replaceAll("\"auto_refresh_time_by_appear\"\\s*:\\s*\\d+", "\"auto_refresh_time_by_appear\":99999999");
            result = result.replaceAll("\"auto_refresh_time_by_active\"\\s*:\\s*\\d+", "\"auto_refresh_time_by_active\":99999999");
            // 关闭切回前台自动刷新开关
            result = result.replaceAll("\"is_back_auto_refresh\"\\s*:\\s*1", "\"is_back_auto_refresh\":0");
        }

        if (ENABLE_CUSTOM_COLUMN) {
            // 修改主页流显示的列数
            result = result.replaceAll("\"column\"\\s*:\\s*\\d+", "\"column\":" + CUSTOM_COLUMN_COUNT);
        }

        if (!result.equals(json)) {
            Log.d(TAG, "[CloudConfig] 已成功修改响应体中的云控参数 (刷新控制/列数)");
        }

        return result;
    }

    // ==================== 响应体读取 / 重建 ====================

    private static String readBodyText(Object responseBody) throws Throwable {
        Object source = responseBody.getClass().getMethod("source").invoke(responseBody);
        source.getClass().getMethod("request", long.class).invoke(source, BODY_REQUEST_BYTES);

        Object buffer = source.getClass().getMethod("getBuffer").invoke(source);
        Object cloned = buffer.getClass().getMethod("clone").invoke(buffer);
        Method readString = cloned.getClass().getMethod("readString", Charset.class);
        return (String) readString.invoke(cloned, StandardCharsets.UTF_8);
    }

    private static Object buildResponseBody(Object originBody, String text) {
        try {
            if (sResponseBodyCls == null) {
                ClassLoader cl = originBody.getClass().getClassLoader();
                sResponseBodyCls = Class.forName("okhttp3.ResponseBody", false, cl);
                sMediaTypeCls = Class.forName("okhttp3.MediaType", false, cl);
                sCreateMethod = sResponseBodyCls.getMethod("create", sMediaTypeCls, String.class);
                sContentTypeMethod = sResponseBodyCls.getMethod("contentType");
            }

            Object mediaType = null;
            try {
                mediaType = sContentTypeMethod.invoke(originBody);
            } catch (Throwable ignored) {
            }
            if (mediaType == null) {
                mediaType = sMediaTypeCls.getMethod("parse", String.class)
                        .invoke(null, "application/json; charset=utf-8");
            }
            return sCreateMethod.invoke(null, mediaType, text);
        } catch (Throwable t) {
            Log.e(TAG, "构造 ResponseBody 失败", t);
            return null;
        }
    }

    // ==================== 广告剔除（括号配平切除） ====================

    private static boolean mayContainAd(String json) {
        return json.contains("\"cm_v2\"")
                || json.contains("\"is_ad_loc\":true")
                || json.contains("\"is_ad_loc\": true")
                || json.contains("\"card_goto\":\"ad_")
                || json.contains("\"card_goto\": \"ad_");
    }

    private static String removeAdItems(String json) {
        int keyAt = json.indexOf("\"items\":[");
        if (keyAt < 0) keyAt = json.indexOf("\"items\": [");
        if (keyAt < 0) return null;

        int bracket = json.indexOf('[', keyAt);
        if (bracket < 0) return null;

        List<int[]> elements = scanArrayElements(json, bracket);
        if (elements.isEmpty()) return null;

        List<int[]> cutSpans = new ArrayList<>();
        for (int[] range : elements) {
            String segment = json.substring(range[0], range[1]);
            if (isAdSegment(segment)) {
                cutSpans.add(expandWithComma(json, range[0], range[1]));
            }
        }
        if (cutSpans.isEmpty()) return null;

        // 从后往前删，避免下标失效
        Collections.sort(cutSpans, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                return b[0] - a[0];
            }
        });

        StringBuilder sb = new StringBuilder(json);
        for (int[] span : cutSpans) {
            // 【新增日志】提取即将被切除的字符串内容并输出
            String removedContent = json.substring(span[0], span[1]);
            if (IS_TESTING) Log.d(TAG, "[AdFilter] ✂️ 成功剔除广告节点数据:\n" + removedContent);
            sb.delete(span[0], span[1]);
        }
        return sb.toString();
    }

    private static List<int[]> scanArrayElements(String s, int start) {
        List<int[]> elements = new ArrayList<>();
        int i = start + 1;
        int depth = 0;
        int elementStart = -1;
        boolean inString = false;
        boolean escaped = false;

        while (i < s.length()) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == BACKSLASH) escaped = true;
                else if (c == '"') inString = false;
            } else {
                if (c == '"') {
                    inString = true;
                    if (depth == 0 && elementStart < 0) elementStart = i;
                } else if (c == '[' || c == '{') {
                    if (depth == 0 && elementStart < 0) elementStart = i;
                    depth++;
                } else if (c == ']' || c == '}') {
                    if (c == ']' && depth == 0) {
                        if (elementStart >= 0) elements.add(new int[]{elementStart, i});
                        return elements;
                    }
                    depth--;
                } else if (c == ',') {
                    if (depth == 0) {
                        if (elementStart >= 0) elements.add(new int[]{elementStart, i});
                        elementStart = -1;
                    }
                } else if (WS.indexOf(c) < 0) {
                    if (depth == 0 && elementStart < 0) elementStart = i;
                }
            }
            i++;
        }
        return elements;
    }

    private static boolean isAdSegment(String seg) {
        if (seg.contains("\"is_ad_loc\":true") || seg.contains("\"is_ad_loc\": true")) return true;
        int keyAt = seg.indexOf("\"card_goto\"");
        if (keyAt >= 0) {
            int colon = seg.indexOf(':', keyAt);
            if (colon >= 0) {
                int quote = seg.indexOf('"', colon + 1);
                if (quote >= 0 && seg.startsWith("ad_", quote + 1)) return true;
            }
        }
        return seg.contains("\"card_type\":\"cm_v2\"") || seg.contains("\"card_type\": \"cm_v2\"");
    }

    private static int[] expandWithComma(String json, int from, int to) {
        int j = to;
        while (j < json.length() && WS.indexOf(json.charAt(j)) >= 0) j++;
        if (j < json.length() && json.charAt(j) == ',') return new int[]{from, j + 1};

        int k = from - 1;
        while (k >= 0 && WS.indexOf(json.charAt(k)) >= 0) k--;
        if (k >= 0 && json.charAt(k) == ',') return new int[]{k, to};
        return new int[]{from, to};
    }

    // ==================== 对象层兜底清洗 ====================

    private static void cleanResponseData(Object generalResponse) {
        try {
            Object pegasusResponse = XposedHelpers.getObjectField(generalResponse, "data");
            if (pegasusResponse == null) return;

            List<?> rawList = null;
            Field targetField = null;
            Class<?> currentClass = pegasusResponse.getClass();

            while (currentClass != null && currentClass != Object.class) {
                for (Field field : currentClass.getDeclaredFields()) {
                    if (List.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        Object val = field.get(pegasusResponse);
                        if (val instanceof List) {
                            rawList = (List<?>) val;
                            targetField = field;
                            break;
                        }
                    }
                }
                if (rawList != null) break;
                currentClass = currentClass.getSuperclass();
            }

            if (rawList == null || rawList.isEmpty()) return;

            List<Object> cleanList = new ArrayList<>();
            int adCount = 0;
            for (Object item : rawList) {
                if (isAdItem(item)) adCount++;
                else cleanList.add(item);
            }

            if (adCount > 0 && targetField != null) {
                targetField.set(pegasusResponse, cleanList);
                Log.d(TAG, "[PegasusFilter] 对象层兜底剔除 " + adCount + " 条广告");
            }
        } catch (Throwable t) {
            Log.e(TAG, "对象层清洗异常", t);
        }
    }

    private static boolean isAdItem(Object item) {
        if (item == null) return false;
        Class<?> clazz = item.getClass();

        if ("aa.e".equals(clazz.getName())) return true;
        for (Class<?> iface : clazz.getInterfaces()) {
            String name = iface.getName();
            if (name.contains("IAdInfo") || name.contains("IAdFeedTagMoveInfo")) return true;
        }
        try {
            Method getBizType = clazz.getMethod("getBizType");
            Object biz = getBizType.invoke(item);
            if (biz != null && biz.toString().toUpperCase().contains("AD")) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ==================== 测试存档 ====================

    private static void saveJsonToFile(String jsonString) {
        try {
            Application currentApp = AndroidAppHelper.currentApplication();
            if (currentApp == null) return;
            File dir = currentApp.getExternalFilesDir(null);
            if (dir == null) return;
            File logFile = new File(dir, "bili_ad_jsons.log");
            FileWriter writer = new FileWriter(logFile, true);
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            writer.append("\n================ [").append(timestamp).append("] ================\n");
            writer.append(jsonString).append("\n");
            writer.flush();
            writer.close();
        } catch (Exception e) {
            Log.e(TAG, "保存 JSON 到文件失败", e);
        }
    }
}