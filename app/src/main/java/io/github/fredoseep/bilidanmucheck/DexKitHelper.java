package io.github.fredoseep.bilidanmucheck;

import android.util.Log;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindField;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.FieldsMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class DexKitHelper {

    public String PROGRESS_VIEW_CLASS_NAME = "";
    public String PROGRESS_POINT_CLASS_NAME = "";
    public String PROGRESS_UI_STATE_CLASS_NAME = "";
    public String PROGRESS_MARKER_UI_CLASS_NAME = "";
    public String PROGRESS_DRAW_CLASS_NAME = "";
    public String CHRONOS_ENGINE_CLASS_NAME = "com.bilibili.cron.ChronosEngine";
    public String CHRONOS_PACKAGE_CLASS_NAME = "com.bilibili.cron.ChronosPackage";
    public String CHRONOS_CREATE_PACKAGE_METHOD_NAME = "createPackageFromFile";
    // Newline-separated class#method#stage targets. Names come from DEX, not JADX aliases.
    public String VIP_TRACE_TARGETS = "";

    public String CHRONOS_RPC_CLASS_NAME = "tv.danmaku.biliplayerv2.service.interact.biz.chronos.chronosrpc.a";

    public String INTERNATIONAL_CHRONOS_RPC_CLASS_NAME = "com.bilibili.common.chronoscommon.o";
    public String INTERNATIONAL_INVOKE_METHOD_NAME = "f";
    public String DESCRIPTION_TEXTVIEW_CLASS_NAME = "Ym1.a";
    public String PEGASUS_MODEL_CLASS_NAME = "U9.h";

    // 开屏广告跳过相关的 fallback 值
    public String BASE_SPLASH_CLASS_NAME = "tv.danmaku.bili.splash.ad.page.BaseSplash";
    public String SPLASH_READY_METHOD_NAME = "k8";
    public String SPLASH_SKIP_METHOD_NAME = "Sf";

    // 新增：视频提及控件相关的 fallback 值
    public String VIDEO_MENTIONED_COMPONENT_CLASS_NAME = "com.bilibili.ship.theseus.ugc.intro.videomentioned.module.l";
    public String VIEW_ENTRY_CLASS_NAME = "com.bilibili.app.gemini.ui.UIComponent$ViewEntry";
    public String UI_COMPONENT_B_CLASS_NAME = "com.bilibili.app.gemini.ui.UIComponent$b";


    public String HOME_CARD_ADAPTER_CLASS_NAME = "ct0.c";

    public String CARD_INFO_SETTING_METHOD_NAME = "m";

    // Do not fall back to an obsolete parser or guess a JADX-renamed method.
    public String PEGASUS_RESPONSE_PARSER_CLASS_NAME = "";
    public String PEGASUS_SUS_GSON_PARSER_PARSER_METHOD_NAME = "";

    public String VIP_OPEN_MEMBERSHIP_PREDICT_SCORE_METHOD_NAME = "d";


    public String NATIVE_WEBVIEW_SUB_CLASS_NAME = "com.bilibili.app.comm.bh.NativeWebView$d";
    //NetworkShowToastService
    public String NETWORK_SHOW_TOAST_SERVICE_CLASS_NAME = "com.bilibili.ship.theseus.united.player.mediaplay.network.m";

    public String NETWORK_SHOW_TOAST_SERVICE_METHOD_NAME = "f";

    public String REQUEST_INTERCEPTOR = "";

    public String IS_POP_UP_AD_VALID_METHOD_NAME = "K";

    private static final String CACHE_SCHEMA = "8-progress-legacy-markers";
    private final Set<String> resolvedFields = new HashSet<>();

    //tag0
    private static final String TAG = "dexkitHelper";

    // 开启测试模式，强制扫描并打印结果
    public final boolean IS_TESTING = BuildConfig.DEBUG;

    public void resolve(XC_LoadPackage.LoadPackageParam lpparam) {
        resolvedFields.clear();
        File apkFile = new File(lpparam.appInfo.sourceDir);
        long currentApkTime = apkFile.lastModified();
        long currentModuleTime = 0;
        try {
            String modulePath = DexKitHelper.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            File moduleApk = new File(modulePath);
            currentModuleTime = moduleApk.lastModified();
        } catch (Exception e) {
            Log.d(TAG, "Failed to get module apk time:\n" + Log.getStackTraceString(e));
        }

        File cacheFile = new File(lpparam.appInfo.dataDir, "cache/dexkit_hook_cache.properties");
        Log.d(TAG, "cache file directory: " + cacheFile.getPath().toString());
        Properties cacheProps = new Properties();
        boolean needScan = true;

        if (cacheFile.exists()) {
            try (FileInputStream fis = new FileInputStream(cacheFile)) {
                cacheProps.load(fis);
                String cachedTimeStr = cacheProps.getProperty("apk_last_modified");
                String cachedModuleTimeStr = cacheProps.getProperty("module_last_modified");

                if (cachedTimeStr != null && cachedTimeStr.equals(String.valueOf(currentApkTime))
                        && cachedModuleTimeStr != null && cachedModuleTimeStr.equals(String.valueOf(currentModuleTime))
                        && currentModuleTime != 0
                        && CACHE_SCHEMA.equals(cacheProps.getProperty("cache_schema"))) {
                    Log.d(TAG, "Hit DexKit cache. Neither host nor module updated, skipping scan.");
                    PROGRESS_VIEW_CLASS_NAME = cacheProps.getProperty("PROGRESS_VIEW_CLASS_NAME", "");
                    PROGRESS_POINT_CLASS_NAME = cacheProps.getProperty("PROGRESS_POINT_CLASS_NAME", "");
                    PROGRESS_UI_STATE_CLASS_NAME = cacheProps.getProperty("PROGRESS_UI_STATE_CLASS_NAME", "");
                    PROGRESS_MARKER_UI_CLASS_NAME = cacheProps.getProperty("PROGRESS_MARKER_UI_CLASS_NAME", "");
                    PROGRESS_DRAW_CLASS_NAME = cacheProps.getProperty("PROGRESS_DRAW_CLASS_NAME", "");
                    CHRONOS_ENGINE_CLASS_NAME = cacheProps.getProperty("CHRONOS_ENGINE_CLASS_NAME", CHRONOS_ENGINE_CLASS_NAME);
                    CHRONOS_PACKAGE_CLASS_NAME = cacheProps.getProperty("CHRONOS_PACKAGE_CLASS_NAME", CHRONOS_PACKAGE_CLASS_NAME);
                    CHRONOS_CREATE_PACKAGE_METHOD_NAME = cacheProps.getProperty("CHRONOS_CREATE_PACKAGE_METHOD_NAME", CHRONOS_CREATE_PACKAGE_METHOD_NAME);
                    VIP_TRACE_TARGETS = cacheProps.getProperty("VIP_TRACE_TARGETS", VIP_TRACE_TARGETS);
                    CHRONOS_RPC_CLASS_NAME = cacheProps.getProperty("CHRONOS_RPC_CLASS_NAME", CHRONOS_RPC_CLASS_NAME);
                    DESCRIPTION_TEXTVIEW_CLASS_NAME = cacheProps.getProperty("DESCRIPTION_TEXTVIEW_CLASS_NAME", DESCRIPTION_TEXTVIEW_CLASS_NAME);
                    INTERNATIONAL_CHRONOS_RPC_CLASS_NAME = cacheProps.getProperty("INTERNATIONAL_CHRONOS_RPC_CLASS_NAME", INTERNATIONAL_CHRONOS_RPC_CLASS_NAME);
                    INTERNATIONAL_INVOKE_METHOD_NAME = cacheProps.getProperty("INTERNATIONAL_INVOKE_METHOD_NAME", INTERNATIONAL_INVOKE_METHOD_NAME);
                    PEGASUS_MODEL_CLASS_NAME = cacheProps.getProperty("PEGASUS_MODEL_CLASS_NAME", PEGASUS_MODEL_CLASS_NAME);

                    BASE_SPLASH_CLASS_NAME = cacheProps.getProperty("BASE_SPLASH_CLASS_NAME", BASE_SPLASH_CLASS_NAME);
                    SPLASH_READY_METHOD_NAME = cacheProps.getProperty("SPLASH_READY_METHOD_NAME", SPLASH_READY_METHOD_NAME);
                    SPLASH_SKIP_METHOD_NAME = cacheProps.getProperty("SPLASH_SKIP_METHOD_NAME", SPLASH_SKIP_METHOD_NAME);

                    // 读取新增的视频提及缓存
                    VIDEO_MENTIONED_COMPONENT_CLASS_NAME = cacheProps.getProperty("VIDEO_MENTIONED_COMPONENT_CLASS_NAME", VIDEO_MENTIONED_COMPONENT_CLASS_NAME);
                    UI_COMPONENT_B_CLASS_NAME = cacheProps.getProperty("UI_COMPONENT_B_CLASS_NAME", UI_COMPONENT_B_CLASS_NAME);
                    VIEW_ENTRY_CLASS_NAME = cacheProps.getProperty("VIEW_ENTRY_CLASS_NAME", VIEW_ENTRY_CLASS_NAME);
                    HOME_CARD_ADAPTER_CLASS_NAME = cacheProps.getProperty("HOME_CARD_ADAPTER_CLASS_NAME", HOME_CARD_ADAPTER_CLASS_NAME);
                    CARD_INFO_SETTING_METHOD_NAME = cacheProps.getProperty("CARD_INFO_SETTING_METHOD_NAME", CARD_INFO_SETTING_METHOD_NAME);

                    PEGASUS_RESPONSE_PARSER_CLASS_NAME = cacheProps.getProperty("PEGASUS_RESPONSE_PARSER_CLASS_NAME", "");
                    PEGASUS_SUS_GSON_PARSER_PARSER_METHOD_NAME = cacheProps.getProperty("PEGASUS_SUS_GSON_PARSER_PARSER_METHOD_NAME", PEGASUS_SUS_GSON_PARSER_PARSER_METHOD_NAME);
                    VIP_OPEN_MEMBERSHIP_PREDICT_SCORE_METHOD_NAME = cacheProps.getProperty("VIP_OPEN_MEMBERSHIP_PREDICT_SCORE_METHOD_NAME", VIP_OPEN_MEMBERSHIP_PREDICT_SCORE_METHOD_NAME);
                    NATIVE_WEBVIEW_SUB_CLASS_NAME = cacheProps.getProperty("NATIVE_WEBVIEW_SUB_CLASS_NAME", NATIVE_WEBVIEW_SUB_CLASS_NAME);
                    NETWORK_SHOW_TOAST_SERVICE_CLASS_NAME = cacheProps.getProperty("NETWORK_SHOW_TOAST_SERVICE_CLASS_NAME", NETWORK_SHOW_TOAST_SERVICE_CLASS_NAME);
                    NETWORK_SHOW_TOAST_SERVICE_METHOD_NAME = cacheProps.getProperty("NETWORK_SHOW_TOAST_SERVICE_METHOD_NAME", NETWORK_SHOW_TOAST_SERVICE_METHOD_NAME);
                    REQUEST_INTERCEPTOR = cacheProps.getProperty("REQUEST_INTERCEPTOR", REQUEST_INTERCEPTOR);
                    IS_POP_UP_AD_VALID_METHOD_NAME = cacheProps.getProperty("IS_POP_UP_AD_VALID_METHOD_NAME", IS_POP_UP_AD_VALID_METHOD_NAME);

                    //tag1
                    needScan = false;
                }
            } catch (Exception e) {
                Log.d(TAG, "Failed to read cache, forcing rescan:\n" + Log.getStackTraceString(e));
            }
        }
        if (needScan || IS_TESTING) {
            Log.d(TAG, "Starting DexKit deep scan...");
            try (DexKitBridge bridge = DexKitBridge.create(lpparam.appInfo.sourceDir)) {
                if (bridge == null) {
                    Log.d(TAG, "DexKit initialization failed!");
                    return;
                }




                scan("ProgressLegacyMarkers", () -> {
                    ClassData view = requireUnique("Progress.legacyView", bridge.findClass(FindClass.create()
                            .matcher(ClassMatcher.create().usingStrings("PlayerDotSeekBar"))));
                    ClassData point = requireUnique("Progress.legacyPoint", bridge.findClass(FindClass.create()
                            .matcher(ClassMatcher.create().addMethod(MethodMatcher.create().name("<init>")
                                    .paramTypes("int", "int", "boolean", "java.lang.Integer", "java.lang.Float",
                                            "kotlin.jvm.functions.Function1")))));
                    PROGRESS_VIEW_CLASS_NAME = view.getName();
                    PROGRESS_POINT_CLASS_NAME = point.getName();
                    resolvedFields.add("PROGRESS_VIEW_CLASS_NAME");
                    resolvedFields.add("PROGRESS_POINT_CLASS_NAME");
                    Log.d(TAG, "[ProgressResolve] legacyView=" + view.getName() + ", point=" + point.getName());
                });

                scan("ProgressMarkers", () -> {
                    ClassData state = requireUnique("Progress.uiState", bridge.findClass(FindClass.create()
                            .matcher(ClassMatcher.create().usingStrings("PlayerProgressUiState(position="))));
                    ClassData marker = requireUnique("Progress.markerUiState", bridge.findClass(FindClass.create()
                            .matcher(ClassMatcher.create().usingStrings("PlayerProgressMarkerUiState(position="))));
                    ClassData draw = requireUnique("Progress.drawLambda", bridge.findClass(FindClass.create()
                            .matcher(ClassMatcher.create().addField(FieldMatcher.create().type(state.getName()))
                                    .addField(FieldMatcher.create().type("java.util.List"))
                                    .addMethod(MethodMatcher.create().name("invoke")
                                            .paramTypes("java.lang.Object").returnType("java.lang.Object")))));
                    PROGRESS_UI_STATE_CLASS_NAME = state.getName();
                    PROGRESS_MARKER_UI_CLASS_NAME = marker.getName();
                    PROGRESS_DRAW_CLASS_NAME = draw.getName();
                    resolvedFields.add("PROGRESS_UI_STATE_CLASS_NAME");
                    resolvedFields.add("PROGRESS_MARKER_UI_CLASS_NAME");
                    resolvedFields.add("PROGRESS_DRAW_CLASS_NAME");
                    Log.d(TAG, "[ProgressResolve] state=" + state.getName() + ", marker=" + marker.getName()
                            + ", draw=" + draw.getName());
                });

                scan("ChronosNative", () -> {
                    CHRONOS_ENGINE_CLASS_NAME = resolveNativeClass(bridge, "nativeRunPackage", CHRONOS_ENGINE_CLASS_NAME);
                    CHRONOS_PACKAGE_CLASS_NAME = resolveNativeClass(bridge, "nativeCreateFromFile", CHRONOS_PACKAGE_CLASS_NAME);
                    resolvedFields.add("CHRONOS_ENGINE_CLASS_NAME");
                    resolvedFields.add("CHRONOS_PACKAGE_CLASS_NAME");
                    List<MethodData> factories = bridge.findMethod(FindMethod.create()
                            .searchInClass(Collections.singleton(bridge.getClassData(CHRONOS_PACKAGE_CLASS_NAME)))
                            .matcher(MethodMatcher.create().paramTypes("android.content.Context", "java.io.File")
                                    .returnType(CHRONOS_PACKAGE_CLASS_NAME)));
                    CHRONOS_CREATE_PACKAGE_METHOD_NAME = requireUnique("Chronos.packageFactory", factories).getMethodName();
                    resolvedFields.add("CHRONOS_CREATE_PACKAGE_METHOD_NAME");
                    Log.d(TAG, "[ChronosResolve] engine=" + CHRONOS_ENGINE_CLASS_NAME
                            + ", package=" + CHRONOS_PACKAGE_CLASS_NAME + "#" + CHRONOS_CREATE_PACKAGE_METHOD_NAME);
                });
                scan("ChronosRpc", () -> {
                    List<MethodData> result = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create().usingStrings("DmsegLoader")));
                    if (result.isEmpty()) result = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create().usingStrings("CommonDanmakuWorkFlows")));
                    java.util.Set<String> classes = new java.util.LinkedHashSet<>();
                    for (MethodData method : result) classes.add(method.getClassName());
                    CHRONOS_RPC_CLASS_NAME = classes.size() == 1 ? classes.iterator().next() : "";
                    if (!CHRONOS_RPC_CLASS_NAME.isEmpty()) resolvedFields.add("CHRONOS_RPC_CLASS_NAME");
                    Log.d(TAG, "[ChronosResolve] legacy RPC candidates=" + classes
                            + "; enhanced-runner registration is installed independently");
                });

                scan("Description", () -> {
                    List<ClassData> descriptionTextViewClassDataList = bridge.findClass(FindClass.create()
                            .matcher(ClassMatcher.create().usingStrings(
                                    "UgcIntroductionComponent",
                                    "onLongClick DescTagSpan"
                            ))
                    );
                    logMatches("Description.descriptionTextViewClassDataList", descriptionTextViewClassDataList);
                    if (descriptionTextViewClassDataList.isEmpty()) {
                        Log.d(TAG, "DexKit fail to find descriptionTextViewClass");
                    } else {
                        ClassData outerClass = getOuterClass(bridge, descriptionTextViewClassDataList.get(0));
                        if (outerClass != null) {
                            DESCRIPTION_TEXTVIEW_CLASS_NAME = outerClass.getName();
                            resolvedFields.add("DESCRIPTION_TEXTVIEW_CLASS_NAME");
                        } else {
                            DESCRIPTION_TEXTVIEW_CLASS_NAME = descriptionTextViewClassDataList.get(0).getName();
                            resolvedFields.add("DESCRIPTION_TEXTVIEW_CLASS_NAME");
                        }
                    }
                });

                scan("InternationalChronosRpc", () -> {
                    List<ClassData> matches = bridge.findClass(FindClass.create()
                            .matcher(ClassMatcher.create().usingStrings("EnhancedChronosPackageRunner",
                                    "DestroyInputSurface: chronos engine is invalid!")));
                    logMatches("Chronos.runner", matches);
                    java.util.Map<String, MethodData> registrations = new java.util.LinkedHashMap<>();
                    for (ClassData candidate : matches) {
                        ClassData outer = getOuterClass(bridge, candidate);
                        java.util.List<ClassData> scopes = new java.util.ArrayList<>();
                        scopes.add(candidate);
                        if (outer != null) scopes.add(outer);
                        for (MethodData method : bridge.findMethod(FindMethod.create().searchInClass(scopes)
                                .matcher(MethodMatcher.create().returnType("void")
                                        .paramTypes("java.lang.Class", "kotlin.jvm.functions.Function6")))) {
                            registrations.put(method.getClassName() + "#" + method.getMethodName(), method);
                        }
                    }
                    MethodData registration = requireUnique("Chronos.runnerRegistration",
                            new java.util.ArrayList<>(registrations.values()));
                    INTERNATIONAL_CHRONOS_RPC_CLASS_NAME = registration.getClassName();
                    INTERNATIONAL_INVOKE_METHOD_NAME = registration.getMethodName();
                    resolvedFields.add("INTERNATIONAL_CHRONOS_RPC_CLASS_NAME");
                    resolvedFields.add("INTERNATIONAL_INVOKE_METHOD_NAME");
                    Log.d(TAG, "[ChronosResolve] registration=" + INTERNATIONAL_CHRONOS_RPC_CLASS_NAME
                            + "#" + INTERNATIONAL_INVOKE_METHOD_NAME);
                });

                scan("PegasusModel", () -> {
                    ClassMatcher pegasusMatcher = ClassMatcher.create()
                            .addInterface(ClassMatcher.create().className("com.bilibili.pegasus.data.base.BasePegasusPlayerData"))
                            .addField(FieldMatcher.create().type("com.bilibili.adcommon.data.AdInfo"))
                            .addField(FieldMatcher.create().type("com.bilibili.pegasus.HolderExtra"))
                            .addField(FieldMatcher.create().type("com.bilibili.ad.adview.pegasus.data.AdMode"));

                    List<ClassData> pegasusResult = bridge.findClass(FindClass.create().matcher(pegasusMatcher));
                    logMatches("PegasusModel.pegasusResult", pegasusResult);

                    if (!pegasusResult.isEmpty()) {
                        PEGASUS_MODEL_CLASS_NAME = pegasusResult.get(0).getName();
                        resolvedFields.add("PEGASUS_MODEL_CLASS_NAME");
                    } else {
                        Log.d(TAG, "❌ 未匹配到推荐流模型类，退回使用硬编码默认值: " + PEGASUS_MODEL_CLASS_NAME);
                    }
                });

                scan("VideoMentioned", () -> {
                    ClassMatcher mentionedMatcher = ClassMatcher.create()
                            .usingStrings("VideoMentionedModuleComponent.TabContainer (VideoMentionedModuleComponent.kt");

                    List<ClassData> mentionedResult = bridge.findClass(FindClass.create().matcher(mentionedMatcher));
                    logMatches("VideoMentioned.mentionedResult", mentionedResult);


                    if (!mentionedResult.isEmpty()) {
                        ClassData mentionedClassData = mentionedResult.get(0);
                        VIDEO_MENTIONED_COMPONENT_CLASS_NAME = mentionedClassData.getName();
                        resolvedFields.add("VIDEO_MENTIONED_COMPONENT_CLASS_NAME");
                        Log.d(TAG, "Found Video Mentioned Component: " + VIDEO_MENTIONED_COMPONENT_CLASS_NAME);

                        MethodMatcher createViewEntryMatcher = MethodMatcher.create()
                                .paramTypes("android.content.Context", "android.view.ViewGroup");

                        List<MethodData> createMethods = bridge.findMethod(FindMethod.create()
                                .searchInClass(Collections.singleton(mentionedClassData))
                                .matcher(createViewEntryMatcher));
                        logMatches("VideoMentioned.createMethods", createMethods);

                        for (MethodData md : createMethods) {
                            String returnType = md.getReturnTypeName();
                            if (!returnType.equals("java.lang.Object")) {
                                VIEW_ENTRY_CLASS_NAME = returnType;
                                resolvedFields.add("VIEW_ENTRY_CLASS_NAME");
                                Log.d(TAG, "动态推导出 ViewEntry 接口: " + VIEW_ENTRY_CLASS_NAME);
                                break;
                            }
                        }

                        int dollarIndex = VIEW_ENTRY_CLASS_NAME.indexOf('$');
                        if (dollarIndex != -1) {
                            String outerClassPrefix = VIEW_ENTRY_CLASS_NAME.substring(0, dollarIndex + 1);

                            ClassMatcher bMatcher = ClassMatcher.create()
                                    .addInterface(ClassMatcher.create().className(VIEW_ENTRY_CLASS_NAME))
                                    .addMethod(MethodMatcher.create().name("<init>").paramCount(1));

                            List<ClassData> bClassList = bridge.findClass(FindClass.create().matcher(bMatcher));
                            logMatches("VideoMentioned.bClassList", bClassList);

                            for (ClassData bClass : bClassList) {
                                if (bClass.getName().startsWith(outerClassPrefix)) {
                                    UI_COMPONENT_B_CLASS_NAME = bClass.getName();
                                    resolvedFields.add("UI_COMPONENT_B_CLASS_NAME");
                                    Log.d(TAG, "动态推导出 ViewEntry 实现类 (原 subclass b): " + UI_COMPONENT_B_CLASS_NAME);
                                    break;
                                }
                            }
                        }
                    } else {
                        Log.d(TAG, "Failed to find Video Mentioned Component, using fallback.");
                    }
                });

                scan("Splash", () -> {
                    ClassMatcher splashMatcher = ClassMatcher.create()
                            .usingStrings("onSplashReady， realReady = ", "showSkipButton, skip clicked");

                    List<ClassData> splashClassResult = bridge.findClass(FindClass.create().matcher(splashMatcher));
                    logMatches("Splash.splashClassResult", splashClassResult);


                    if (!splashClassResult.isEmpty()) {
                        ClassData splashClassData = splashClassResult.get(0);
                        BASE_SPLASH_CLASS_NAME = splashClassData.getName();
                        resolvedFields.add("BASE_SPLASH_CLASS_NAME");
                        Log.d(TAG, "找到 BaseSplash 类: " + BASE_SPLASH_CLASS_NAME);

                        // 查找 onSplashReady 方法
                        MethodMatcher readyMethodMatcher = MethodMatcher.create()
                                .paramTypes("boolean")
                                .usingStrings("onSplashReady， realReady = ");

                        List<MethodData> readyMethodResult = bridge.findMethod(FindMethod.create()
                                .searchInClass(Collections.singleton(splashClassData))
                                .matcher(readyMethodMatcher));
                        logMatches("Splash.readyMethodResult", readyMethodResult);

                        if (!readyMethodResult.isEmpty()) {
                            SPLASH_READY_METHOD_NAME = readyMethodResult.get(0).getName();
                            resolvedFields.add("SPLASH_READY_METHOD_NAME");
                            Log.d(TAG, "找到 onSplashReady 方法: " + SPLASH_READY_METHOD_NAME);
                        }

                        // 查找 skip clicked 方法
                        MethodMatcher skipMethodMatcher = MethodMatcher.create()
                                .paramCount(0)
                                .usingStrings("showSkipButton, skip clicked");

                        List<MethodData> skipMethodResult = bridge.findMethod(FindMethod.create()
                                .searchInClass(Collections.singleton(splashClassData))
                                .matcher(skipMethodMatcher));
                        logMatches("Splash.skipMethodResult", skipMethodResult);

                        if (!skipMethodResult.isEmpty()) {
                            SPLASH_SKIP_METHOD_NAME = skipMethodResult.get(0).getName();
                            resolvedFields.add("SPLASH_SKIP_METHOD_NAME");
                            Log.d(TAG, "找到 SkipButton 方法: " + SPLASH_SKIP_METHOD_NAME);
                        }
                    } else {
                        Log.d(TAG, "❌ 未匹配到 BaseSplash 类，退回使用硬编码默认值");
                    }
                });



                scan("HomeCard", () -> {
                    List<ClassData> homeCardAdapterClassDataList = bridge.findClass(FindClass.create()
                            .matcher(
                                    ClassMatcher.create()
                                            .usingStrings(
                                                    "BannerV8Data(idx=",
                                                    ", cardType=",
                                                    ", extraRptFields="
                                            )
                            )
                    );
                    logMatches("HomeCard.homeCardAdapterClassDataList", homeCardAdapterClassDataList);
                    HOME_CARD_ADAPTER_CLASS_NAME = requireUnique("HomeCard.homeCardAdapterClassDataList", homeCardAdapterClassDataList).getName();
                    resolvedFields.add("HOME_CARD_ADAPTER_CLASS_NAME");
                    List<MethodData> cardInfoSettingMethodDataList = bridge.findMethod(FindMethod.create()
                            .searchInClass(homeCardAdapterClassDataList)
                            .matcher(
                                    MethodMatcher.create()
                                            .paramCount(1)
                                            .paramTypes("java.util.List")
                            )
                    );
                    logMatches("HomeCard.cardInfoSettingMethodDataList", cardInfoSettingMethodDataList);
                    CARD_INFO_SETTING_METHOD_NAME = requireUnique("HomeCard.cardInfoSettingMethodDataList", cardInfoSettingMethodDataList).getMethodName();
                    resolvedFields.add("CARD_INFO_SETTING_METHOD_NAME");
                });

                scan("PegasusParser", () -> {
                    // The full response parser moved out of PegasusGsonParser.
                    // Use the real DEX declaration; convert2 is a JADX display name.
                    PEGASUS_RESPONSE_PARSER_CLASS_NAME = "";
                    PEGASUS_SUS_GSON_PARSER_PARSER_METHOD_NAME = "";
                    List<MethodData> methods = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .usingStrings("pegasus start parse")
                                    .paramTypes("okhttp3.ResponseBody")
                                    .paramCount(1)
                                    .returnType("com.bilibili.okretro.GeneralResponse")));
                    logMatches("PegasusParser.fullResponse", methods);
                    MethodData method = requireUnique("PegasusParser.fullResponse", methods);
                    PEGASUS_RESPONSE_PARSER_CLASS_NAME = method.getClassName();
                    PEGASUS_SUS_GSON_PARSER_PARSER_METHOD_NAME = method.getMethodName();
                    resolvedFields.add("PEGASUS_RESPONSE_PARSER_CLASS_NAME");
                    resolvedFields.add("PEGASUS_SUS_GSON_PARSER_PARSER_METHOD_NAME");
                    Log.d(TAG, "[PegasusParser] resolved "
                            + PEGASUS_RESPONSE_PARSER_CLASS_NAME + "#"
                            + PEGASUS_SUS_GSON_PARSER_PARSER_METHOD_NAME
                            + "(okhttp3.ResponseBody)");
                });

                scan("VipPredictMethod", () -> {
                    List<MethodData> vipOpenMembershipPredictScoreMethodDataList = bridge.findMethod(FindMethod.create()
                            .searchInClass(requireScope("VipPredictMethod", bridge.findClass(FindClass.create()
                                    .matcher(ClassMatcher.create().className("com.bilibili.tensorflow.model.mem.VipOpenMembershipPredictTFClient"))
                            )))
                            .matcher(MethodMatcher.create()
                                    .paramCount(2)
                                    .paramTypes("java.util.List", "java.nio.ByteBuffer")
                            )
                    );
                    logMatches("VipPredictMethod.vipOpenMembershipPredictScoreMethodDataList", vipOpenMembershipPredictScoreMethodDataList);
                    VIP_OPEN_MEMBERSHIP_PREDICT_SCORE_METHOD_NAME = requireUnique("VipPredictMethod.vipOpenMembershipPredictScoreMethodDataList", vipOpenMembershipPredictScoreMethodDataList).getMethodName();
                    resolvedFields.add("VIP_OPEN_MEMBERSHIP_PREDICT_SCORE_METHOD_NAME");
                });



                scan("VipTraceTargets", () -> {
                    StringBuilder targets = new StringBuilder();
                    collectTraceTargets(bridge, targets, "com.bilibili.tensorflow.model.mem.VipOpenMembershipPredictTFClient", "VIP_METHOD", true);
                    collectTraceTargets(bridge, targets, "com.bilibili.tensorflow.model.mem.OgvOpenMembershipPredictTFClient", "OGV_METHOD", true);
                    collectModelTraceTargets(bridge, targets);
                    collectTraceTargets(bridge, targets, "com.bilibili.tensorflow.data.mem.TfOpenMemberReportEventLoader", "EVENT_METHOD", true);
                    collectTraceTargets(bridge, targets, "com.bilibili.tensorflow.jsb.TfLiteModelService", "JSB_METHOD", false);
                    java.util.Set<String> configSites = new java.util.LinkedHashSet<>();
                    for (MethodData method : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                            .usingStrings("tf_ogv_report_open_membership_switch")))) {
                        configSites.add(method.getClassName() + "#" + method.getMethodName() + "#CONFIG_SITE\n");
                    }
                    for (String site : configSites) targets.append(site);
                    VIP_TRACE_TARGETS = targets.toString();
                    if (!VIP_TRACE_TARGETS.isEmpty()) resolvedFields.add("VIP_TRACE_TARGETS");
                    Log.d(TAG, "[VipResolve] targets=" + VIP_TRACE_TARGETS.replace('\n', ';'));
                });

                scan("NativeWebView", () -> {
                    List<ClassData> nativeWebViewSubClassDataList = bridge.findClass(FindClass.create()
                            .searchPackages("com.bilibili.app.comm")
                            .matcher(ClassMatcher.create()
                                    // 1. 父类必须是 WebViewClient
                                    .superClass("android.webkit.WebViewClient")
                                    // 2. 字段总数严格等于 2 (去掉对未知类名的字段类型匹配)
                                    .fieldCount(2)
                                    // 3. 实现了 shouldInterceptRequest(WebView, WebResourceRequest)
                                    .addMethod(MethodMatcher.create()
                                            .name("shouldInterceptRequest")
                                            .paramTypes(
                                                    "android.webkit.WebView",
                                                    "android.webkit.WebResourceRequest"
                                            )
                                    )
                                    // 4. 重写了 Ssl 错误处理（加这一层双保险，彻底排除其它无关实现）
                                    .addMethod(MethodMatcher.create()
                                            .name("onReceivedSslError")
                                            .paramTypes(
                                                    "android.webkit.WebView",
                                                    "android.webkit.SslErrorHandler",
                                                    "android.net.http.SslError"
                                            )
                                    )
                            )
                    );
                    logMatches("NativeWebView.nativeWebViewSubClassDataList", nativeWebViewSubClassDataList);
                    NATIVE_WEBVIEW_SUB_CLASS_NAME = requireUnique("NativeWebView.nativeWebViewSubClassDataList", nativeWebViewSubClassDataList).getName();
                    resolvedFields.add("NATIVE_WEBVIEW_SUB_CLASS_NAME");
                });

                scan("NetworkToast", () -> {
                    List<ClassData> networkShowToastServiceClassDataList = bridge.findClass(FindClass.create()
                            .searchPackages("com.bilibili.ship.theseus.united.player.mediaplay.network")
                            .matcher(ClassMatcher.create()
                                    .usingStrings("show cellular toast, app once: ", ", already show: ")
                            )
                    );
                    logMatches("NetworkToast.networkShowToastServiceClassDataList", networkShowToastServiceClassDataList);
                    NETWORK_SHOW_TOAST_SERVICE_CLASS_NAME = requireUnique("NetworkToast.networkShowToastServiceClassDataList", networkShowToastServiceClassDataList).getName();
                    resolvedFields.add("NETWORK_SHOW_TOAST_SERVICE_CLASS_NAME");

                    List<MethodData> networkShowToastServiceMethodDataList = bridge.findMethod(FindMethod.create()
                            .searchInClass(networkShowToastServiceClassDataList)
                            .matcher(MethodMatcher.create()
                                    .usingStrings("NetworkShowToastService", "not china sim user, skip free data guide toast")
                            )
                    );
                    logMatches("NetworkToast.networkShowToastServiceMethodDataList", networkShowToastServiceMethodDataList);
                    NETWORK_SHOW_TOAST_SERVICE_METHOD_NAME = requireUnique("NetworkToast.networkShowToastServiceMethodDataList", networkShowToastServiceMethodDataList).getMethodName();
                    resolvedFields.add("NETWORK_SHOW_TOAST_SERVICE_METHOD_NAME");
                });

                scan("SpaceInterceptor", () -> {
                    REQUEST_INTERCEPTOR = "";
                    Class<?> api = Class.forName("com.bilibili.app.authorspace.local.utils.SpaceApi",
                            false, lpparam.classLoader);
                    java.util.Set<Class<?>> targets = new java.util.LinkedHashSet<>();
                    for (Method method : api.getDeclaredMethods()) {
                        // Bind to the endpoint annotation, not a potentially renamed method.
                        boolean spaceEndpoint = false;
                        for (java.lang.annotation.Annotation annotation : method.getDeclaredAnnotations()) {
                            if (annotation.annotationType().getName().equals("retrofit2.http.GET")) {
                                String endpoint = String.valueOf(annotation.annotationType()
                                        .getMethod("value").invoke(annotation));
                                spaceEndpoint = endpoint.equals("https://app.bilibili.com/x/v2/space")
                                        || endpoint.equals("/x/v2/space");
                            }
                        }
                        if (!spaceEndpoint) continue;
                        for (java.lang.annotation.Annotation annotation : method.getDeclaredAnnotations()) {
                            if (annotation.annotationType().getName().equals("com.bilibili.okretro.anno.RequestInterceptor")) {
                                Object target = annotation.annotationType().getMethod("value").invoke(annotation);
                                if (target instanceof Class<?>) targets.add((Class<?>) target);
                            }
                        }
                    }
                    if (targets.size() != 1) throw new IllegalStateException("SpaceApi interceptor count=" + targets.size());
                    Class<?> type = targets.iterator().next();
                    type.getDeclaredConstructor();
                    REQUEST_INTERCEPTOR = type.getName();
                    resolvedFields.add("REQUEST_INTERCEPTOR");
                    Log.d(TAG, "[SpaceResolve] interceptor=" + REQUEST_INTERCEPTOR + " (from SpaceApi annotation)");
                });

                scan("PausedPage", () -> {
                    List<ClassData> pausedPageServiceClassDataList = bridge.findClass(FindClass.create()
                            .matcher(ClassMatcher.create()
                                    .className("com.bilibili.ship.theseus.united.page.pausedpage.PausedPageService")
                            )
                    );
                    logMatches("PausedPage.pausedPageServiceClassDataList", pausedPageServiceClassDataList);
                    if(pausedPageServiceClassDataList.isEmpty())Log.d(TAG,"pausedPageServiceClassDataList is empty");
                    else {
                        List<MethodData> isPopUpAdValidMethodDataList = bridge.findMethod(FindMethod.create()
                                .searchInClass(pausedPageServiceClassDataList)
                                .matcher(MethodMatcher.create()
                                        .paramCount(1)
                                        .returnType("boolean")
                                        .paramTypes("kotlinx.coroutines.Job")
                                )
                        );
                        logMatches("PausedPage.isPopUpAdValidMethodDataList", isPopUpAdValidMethodDataList);
                        if(isPopUpAdValidMethodDataList.isEmpty())Log.d(TAG,"isPopUpAdValidMethodDataList is empty");
                        else {
                            IS_POP_UP_AD_VALID_METHOD_NAME = requireUnique("PausedPage.isPopUpAdValidMethodDataList", isPopUpAdValidMethodDataList).getMethodName();
                            resolvedFields.add("IS_POP_UP_AD_VALID_METHOD_NAME");
                        }
                    }
                });

                cacheProps.clear();
                cacheProps.setProperty("cache_schema", CACHE_SCHEMA);
                cacheProps.setProperty("apk_last_modified", String.valueOf(currentApkTime));
                cacheProps.setProperty("module_last_modified", String.valueOf(currentModuleTime));
                if (resolvedFields.contains("PROGRESS_VIEW_CLASS_NAME")) cacheProps.setProperty("PROGRESS_VIEW_CLASS_NAME", PROGRESS_VIEW_CLASS_NAME);
                if (resolvedFields.contains("PROGRESS_POINT_CLASS_NAME")) cacheProps.setProperty("PROGRESS_POINT_CLASS_NAME", PROGRESS_POINT_CLASS_NAME);
                if (resolvedFields.contains("PROGRESS_UI_STATE_CLASS_NAME")) cacheProps.setProperty("PROGRESS_UI_STATE_CLASS_NAME", PROGRESS_UI_STATE_CLASS_NAME);
                if (resolvedFields.contains("PROGRESS_MARKER_UI_CLASS_NAME")) cacheProps.setProperty("PROGRESS_MARKER_UI_CLASS_NAME", PROGRESS_MARKER_UI_CLASS_NAME);
                if (resolvedFields.contains("PROGRESS_DRAW_CLASS_NAME")) cacheProps.setProperty("PROGRESS_DRAW_CLASS_NAME", PROGRESS_DRAW_CLASS_NAME);
                if (resolvedFields.contains("CHRONOS_ENGINE_CLASS_NAME")) cacheProps.setProperty("CHRONOS_ENGINE_CLASS_NAME", CHRONOS_ENGINE_CLASS_NAME);
                if (resolvedFields.contains("CHRONOS_PACKAGE_CLASS_NAME")) cacheProps.setProperty("CHRONOS_PACKAGE_CLASS_NAME", CHRONOS_PACKAGE_CLASS_NAME);
                if (resolvedFields.contains("CHRONOS_CREATE_PACKAGE_METHOD_NAME")) cacheProps.setProperty("CHRONOS_CREATE_PACKAGE_METHOD_NAME", CHRONOS_CREATE_PACKAGE_METHOD_NAME);
                if (resolvedFields.contains("VIP_TRACE_TARGETS")) cacheProps.setProperty("VIP_TRACE_TARGETS", VIP_TRACE_TARGETS);
                if (resolvedFields.contains("CHRONOS_RPC_CLASS_NAME")) cacheProps.setProperty("CHRONOS_RPC_CLASS_NAME", CHRONOS_RPC_CLASS_NAME);
                if (resolvedFields.contains("DESCRIPTION_TEXTVIEW_CLASS_NAME")) cacheProps.setProperty("DESCRIPTION_TEXTVIEW_CLASS_NAME", DESCRIPTION_TEXTVIEW_CLASS_NAME);
                if (resolvedFields.contains("INTERNATIONAL_CHRONOS_RPC_CLASS_NAME")) cacheProps.setProperty("INTERNATIONAL_CHRONOS_RPC_CLASS_NAME", INTERNATIONAL_CHRONOS_RPC_CLASS_NAME);
                if (resolvedFields.contains("INTERNATIONAL_INVOKE_METHOD_NAME")) cacheProps.setProperty("INTERNATIONAL_INVOKE_METHOD_NAME", INTERNATIONAL_INVOKE_METHOD_NAME);
                if (resolvedFields.contains("PEGASUS_MODEL_CLASS_NAME")) cacheProps.setProperty("PEGASUS_MODEL_CLASS_NAME", PEGASUS_MODEL_CLASS_NAME);

                if (resolvedFields.contains("BASE_SPLASH_CLASS_NAME")) cacheProps.setProperty("BASE_SPLASH_CLASS_NAME", BASE_SPLASH_CLASS_NAME);
                if (resolvedFields.contains("SPLASH_READY_METHOD_NAME")) cacheProps.setProperty("SPLASH_READY_METHOD_NAME", SPLASH_READY_METHOD_NAME);
                if (resolvedFields.contains("SPLASH_SKIP_METHOD_NAME")) cacheProps.setProperty("SPLASH_SKIP_METHOD_NAME", SPLASH_SKIP_METHOD_NAME);

                // 保存新增的缓存
                if (resolvedFields.contains("VIDEO_MENTIONED_COMPONENT_CLASS_NAME")) cacheProps.setProperty("VIDEO_MENTIONED_COMPONENT_CLASS_NAME", VIDEO_MENTIONED_COMPONENT_CLASS_NAME);
                if (resolvedFields.contains("UI_COMPONENT_B_CLASS_NAME")) cacheProps.setProperty("UI_COMPONENT_B_CLASS_NAME", UI_COMPONENT_B_CLASS_NAME);
                if (resolvedFields.contains("VIEW_ENTRY_CLASS_NAME")) cacheProps.setProperty("VIEW_ENTRY_CLASS_NAME", VIEW_ENTRY_CLASS_NAME);
                if (resolvedFields.contains("HOME_CARD_ADAPTER_CLASS_NAME")) cacheProps.setProperty("HOME_CARD_ADAPTER_CLASS_NAME", HOME_CARD_ADAPTER_CLASS_NAME);
                if (resolvedFields.contains("CARD_INFO_SETTING_METHOD_NAME")) cacheProps.setProperty("CARD_INFO_SETTING_METHOD_NAME", CARD_INFO_SETTING_METHOD_NAME);

                if (resolvedFields.contains("PEGASUS_RESPONSE_PARSER_CLASS_NAME")) cacheProps.setProperty("PEGASUS_RESPONSE_PARSER_CLASS_NAME", PEGASUS_RESPONSE_PARSER_CLASS_NAME);
                if (resolvedFields.contains("PEGASUS_SUS_GSON_PARSER_PARSER_METHOD_NAME")) cacheProps.setProperty("PEGASUS_SUS_GSON_PARSER_PARSER_METHOD_NAME", PEGASUS_SUS_GSON_PARSER_PARSER_METHOD_NAME);
                if (resolvedFields.contains("VIP_OPEN_MEMBERSHIP_PREDICT_SCORE_METHOD_NAME")) cacheProps.setProperty("VIP_OPEN_MEMBERSHIP_PREDICT_SCORE_METHOD_NAME", VIP_OPEN_MEMBERSHIP_PREDICT_SCORE_METHOD_NAME);
                if (resolvedFields.contains("NATIVE_WEBVIEW_SUB_CLASS_NAME")) cacheProps.setProperty("NATIVE_WEBVIEW_SUB_CLASS_NAME", NATIVE_WEBVIEW_SUB_CLASS_NAME);
                if (resolvedFields.contains("NETWORK_SHOW_TOAST_SERVICE_CLASS_NAME")) cacheProps.setProperty("NETWORK_SHOW_TOAST_SERVICE_CLASS_NAME", NETWORK_SHOW_TOAST_SERVICE_CLASS_NAME);
                if (resolvedFields.contains("NETWORK_SHOW_TOAST_SERVICE_METHOD_NAME")) cacheProps.setProperty("NETWORK_SHOW_TOAST_SERVICE_METHOD_NAME", NETWORK_SHOW_TOAST_SERVICE_METHOD_NAME);
                if (resolvedFields.contains("REQUEST_INTERCEPTOR")) cacheProps.setProperty("REQUEST_INTERCEPTOR", REQUEST_INTERCEPTOR);
                if (resolvedFields.contains("IS_POP_UP_AD_VALID_METHOD_NAME")) cacheProps.setProperty("IS_POP_UP_AD_VALID_METHOD_NAME", IS_POP_UP_AD_VALID_METHOD_NAME);

                //tag2
                cacheFile.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                    cacheProps.store(fos, "DexKit Obfuscation Cache for BiliBili");
                    Log.d(TAG, "DexKit scan complete, results saved to cache.");
                }
            } catch (Exception e) {
                Log.d(TAG, "Exception during DexKit scan:\n" + Log.getStackTraceString(e));
            }
        }
    }

    private static String resolveNativeClass(DexKitBridge bridge, String nativeName, String fallback) {
        List<ClassData> candidates = bridge.findClass(FindClass.create()
                .matcher(ClassMatcher.create().addMethod(MethodMatcher.create().name(nativeName))));
        logMatches("Chronos." + nativeName, candidates);
        if (candidates.size() == 1) return candidates.get(0).getName();
        if (candidates.isEmpty() && bridge.getClassData(fallback) != null) return fallback;
        throw new IllegalStateException(nativeName + ": ambiguous or missing class, count=" + candidates.size());
    }

    /**
     * Resolve the model-file preparation singleton by structure, never by an obfuscated letter.
     * Verified against 9.19.0: two File fields, String state, Mutex and two suspend methods.
     * Keep the other trace targets available when this optional model target cannot be resolved.
     */
    private static void collectModelTraceTargets(DexKitBridge bridge, StringBuilder output) {
        try {
            List<ClassData> candidates = bridge.findClass(FindClass.create()
                    .searchPackages("com.bilibili.tensorflow.model.mem")
                    .matcher(ClassMatcher.create()
                            .addField(FieldMatcher.create().type("java.io.File"))
                            .addField(FieldMatcher.create().type("java.lang.String"))
                            .addField(FieldMatcher.create().type("kotlinx.coroutines.sync.Mutex"))
                            .addMethod(MethodMatcher.create()
                                    .paramTypes("kotlin.coroutines.jvm.internal.ContinuationImpl")
                                    .returnType("java.lang.Object"))));
            logMatches("VipModel.structure", candidates);
            java.util.List<ClassData> verified = new java.util.ArrayList<>();
            for (ClassData candidate : candidates) {
                String name = candidate.getName();
                List<FieldData> files = bridge.findField(FindField.create()
                        .matcher(FieldMatcher.create().declaredClass(name).type("java.io.File")));
                List<FieldData> singleton = bridge.findField(FindField.create()
                        .matcher(FieldMatcher.create().declaredClass(name).type(name)));
                List<MethodData> suspendMethods = bridge.findMethod(FindMethod.create()
                        .searchInClass(Collections.singleton(candidate))
                        .matcher(MethodMatcher.create()
                                .paramTypes("kotlin.coroutines.jvm.internal.ContinuationImpl")
                                .returnType("java.lang.Object")));
                boolean accepted = files.size() >= 2 && !singleton.isEmpty()
                        && suspendMethods.size() >= 2;
                Log.d(TAG, "[VipResolve] model candidate=" + name + ", files=" + files.size()
                        + ", singletonFields=" + singleton.size() + ", suspendMethods="
                        + suspendMethods.size() + ", accepted=" + accepted);
                if (accepted) verified.add(candidate);
            }
            ClassData model = requireUnique("VipModel.verified", verified);
            collectTraceTargets(bridge, output, model.getName(), "MODEL_METHOD", false);
            Log.d(TAG, "[VipResolve] MODEL_METHOD resolved=" + model.getName());
        } catch (Exception | LinkageError error) {
            Log.d(TAG, "[VipResolve] MODEL_METHOD unresolved; other targets continue\n"
                    + Log.getStackTraceString(error));
        }
    }

    private static void collectTraceTargets(DexKitBridge bridge, StringBuilder output,
                                            String className, String stage, boolean stringFallback) {
        ClassData type = bridge.getClassData(className);
        if (type == null && stringFallback) {
            String simpleName = className.substring(className.lastIndexOf('.') + 1);
            List<ClassData> candidates = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create().usingStrings(simpleName)));
            if (candidates.size() == 1) type = candidates.get(0);
        }
        if (type == null) {
            Log.d(TAG, "[VipResolve] missing " + className);
            return;
        }
        List<MethodData> methods = bridge.findMethod(FindMethod.create()
                .searchInClass(Collections.singleton(type)).matcher(MethodMatcher.create()));
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (MethodData method : methods) {
            String name = method.getMethodName();
            if (!name.startsWith("<") && !name.equals("equals") && !name.equals("hashCode")
                    && !name.equals("toString")) names.add(name);
        }
        for (String name : names) output.append(type.getName()).append('#').append(name)
                .append('#').append(stage).append('\n');
    }

    @FunctionalInterface
    private interface ScanTask {
        void run() throws Exception;
    }

    private static void scan(String name, ScanTask task) {
        try {
            Log.d(TAG, "[DexKitScan] START " + name);
            task.run();
            Log.d(TAG, "[DexKitScan] END " + name);
        } catch (Exception | LinkageError e) {
            Log.d(TAG, "[DexKitScan] FAILED " + name + " (后续扫描继续)\n"
                    + Log.getStackTraceString(e));
        }
    }

    private static void logMatches(String name, List<?> results) {
        Log.d(TAG, "[DexKitMatch] " + name + ", count="
                + (results == null ? 0 : results.size()));
    }

    private static <T> T requireUnique(String name, List<T> results) {
        if (results == null || results.size() != 1) {
            throw new IllegalStateException(name + ": expected 1 result, actual="
                    + (results == null ? 0 : results.size()));
        }
        return results.get(0);
    }

    private static List<ClassData> requireScope(String name, List<ClassData> results) {
        requireUnique(name + ".classScope", results);
        return results;
    }

    public ClassData getOuterClass(DexKitBridge bridge, ClassData innerClassData) {
        String innerClassName = innerClassData.getName();
        int dollarIndex = innerClassName.indexOf('$');
        if (dollarIndex == -1) {
            return null;
        }
        String outerClassName = innerClassName.substring(0, dollarIndex);
        return bridge.getClassData(outerClassName);
    }

    private static void listDataPrint(List<?> dataList) {
        if (dataList.isEmpty()) {
            Log.d(TAG, "error: data list is empty");
            return;
        }
        Log.d(TAG, "found " + dataList.size() + " items");
        for (Object data : dataList) {
            if (data instanceof ClassData) {
                Log.d(TAG, "found class name: " + ((ClassData) data).getName());
            } else if (data instanceof MethodData) {
                Log.d(TAG, "found method name: " + ((MethodData) data).getName());
            } else if (data instanceof FieldData) {
                Log.d(TAG, "found field name: " + ((FieldData) data).getName());
            }
        }
    }
}
