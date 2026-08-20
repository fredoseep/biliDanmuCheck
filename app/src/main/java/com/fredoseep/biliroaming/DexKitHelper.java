package com.fredoseep.biliroaming;

import android.util.Log;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindField;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class DexKitHelper {

    public String albumRecycleViewHolderClassName = "PJ0.e"; // 默认 fallback 值
    public String publishArchiveCollectionFieldName = "a";
    public String albumSelectPageViewClassName = "OH0.X";
    public String RECYCLER_VIEW_FIELD_NAME = "f";
    public String CHRONOS_RPC_CLASS_NAME = "tv.danmaku.biliplayerv2.service.interact.biz.chronos.chronosrpc.a";

    public String INTERNATIONAL_CHRONOS_RPC_CLASS_NAME = "com.bilibili.common.chronoscommon.o";

    public String INTERNATIONAL_INVOKE_METHOD_NAME = "f";

    public String DESCRIPTION_TEXTVIEW_CLASS_NAME = "Ym1.a";

    public final boolean IS_TESTING = false;

    /**
     * 解析并加载混淆变量。如果缓存有效则直接加载，否则启动 DexKit 扫描。
     */
    public void resolve(XC_LoadPackage.LoadPackageParam lpparam) {
        File apkFile = new File(lpparam.appInfo.sourceDir);
        long currentApkTime = apkFile.lastModified();
        File cacheFile = new File(lpparam.appInfo.dataDir, "cache/dexkit_hook_cache.properties");
        MainHook.log("cache file directory: "+cacheFile.getPath().toString());
        Properties cacheProps = new Properties();
        boolean needScan = true;

        if (cacheFile.exists()) {
            try (FileInputStream fis = new FileInputStream(cacheFile)) {
                cacheProps.load(fis);
                String cachedTimeStr = cacheProps.getProperty("apk_last_modified");

                if (cachedTimeStr != null && cachedTimeStr.equals(String.valueOf(currentApkTime))) {
                    MainHook.log("命中 DexKit 缓存，APK 未更新，跳过扫描");
                    albumRecycleViewHolderClassName = cacheProps.getProperty("albumRecycleViewHolderClassName", albumRecycleViewHolderClassName);
                    publishArchiveCollectionFieldName = cacheProps.getProperty("publishArchiveCollectionFieldName", publishArchiveCollectionFieldName);
                    albumSelectPageViewClassName = cacheProps.getProperty("albumSelectPageViewClassName", albumSelectPageViewClassName);
                    RECYCLER_VIEW_FIELD_NAME = cacheProps.getProperty("RECYCLER_VIEW_FIELD_NAME", RECYCLER_VIEW_FIELD_NAME);
                    CHRONOS_RPC_CLASS_NAME = cacheProps.getProperty("chronosRpcClassName", CHRONOS_RPC_CLASS_NAME);
                    DESCRIPTION_TEXTVIEW_CLASS_NAME = cacheProps.getProperty("DESCRIPTION_TEXTVIEW_CLASS_NAME", DESCRIPTION_TEXTVIEW_CLASS_NAME);
                    INTERNATIONAL_CHRONOS_RPC_CLASS_NAME = cacheProps.getProperty("INTERNATIONAL_CHRONOS_RPC_CLASS_NAME", INTERNATIONAL_CHRONOS_RPC_CLASS_NAME);
                    INTERNATIONAL_INVOKE_METHOD_NAME = cacheProps.getProperty("INTERNATIONAL_INVOKE_METHOD_NAME", INTERNATIONAL_INVOKE_METHOD_NAME);

                    needScan = false;
                }
            } catch (Exception e) {
                MainHook.log("读取缓存失败，将重新扫描: " + e.getMessage());
            }
        }

        if (needScan||IS_TESTING) {
            MainHook.log(" B站版本更新或首次运行，启动 DexKit 深度扫描...");
            try (DexKitBridge bridge = DexKitBridge.create(lpparam.appInfo.sourceDir)) {
                if (bridge == null) {
                    MainHook.log("❌ DexKit 初始化失败！");
                    return;
                }


                MethodMatcher matcher1 = MethodMatcher.create()
                        .name("onBindViewHolder")
                        .paramTypes("androidx.recyclerview.widget.RecyclerView$ViewHolder", "int")
                        .usingStrings("这里填入独有字符串");

                List<MethodData> result1 = bridge.findMethod(FindMethod.create().matcher(matcher1));
                if (!result1.isEmpty()) {
                    albumRecycleViewHolderClassName = result1.get(0).getClassName();
                    MainHook.log("找到 AlbumRecycleViewHolder: " + albumRecycleViewHolderClassName);

                    FieldMatcher fieldMatcher = FieldMatcher.create()
                            .declaredClass(albumRecycleViewHolderClassName)
                            .type("java.util.List");

                    List<FieldData> fields = bridge.findField(FindField.create().matcher(fieldMatcher));
                    if (!fields.isEmpty()) {
                        publishArchiveCollectionFieldName = fields.get(0).getName();
                        MainHook.log("找到 List 字段名: " + publishArchiveCollectionFieldName);
                    }
                }


                ClassMatcher viewBindingMatcher = ClassMatcher.create()
                        .addInterface(ClassMatcher.create().className("androidx.viewbinding.ViewBinding"))
                        .addMethod(MethodMatcher.create().name("inflate").paramTypes("android.view.LayoutInflater", "android.view.ViewGroup", "boolean"))
                        .addField(FieldMatcher.create().type("androidx.recyclerview.widget.RecyclerView"))
                        .addField(FieldMatcher.create().type("com.bilibili.magicasakura.widgets.TintConstraintLayout"))
                        .addField(FieldMatcher.create().type("com.bilibili.magicasakura.widgets.TintLinearLayout"));

                List<ClassData> result2 = bridge.findClass(FindClass.create().matcher(viewBindingMatcher));
                if (!result2.isEmpty()) {
                    albumSelectPageViewClassName = result2.get(0).getName();
                    MainHook.log("找到 AlbumSelectPageView: " + albumSelectPageViewClassName);

                    FieldMatcher rvFieldMatcher = FieldMatcher.create()
                            .declaredClass(albumSelectPageViewClassName)
                            .type("androidx.recyclerview.widget.RecyclerView");

                    List<FieldData> fields = bridge.findField(FindField.create().matcher(rvFieldMatcher));
                    if (!fields.isEmpty()) {
                        RECYCLER_VIEW_FIELD_NAME = fields.get(0).getName();
                        MainHook.log("找到 RecyclerView 字段名: " + RECYCLER_VIEW_FIELD_NAME);
                    }
                }

                MethodMatcher matcher3 = MethodMatcher.create()
                        .usingStrings("DmsegLoader");

                List<MethodData> result3 = bridge.findMethod(FindMethod.create().matcher(matcher3));
                if (!result3.isEmpty()) {
                    CHRONOS_RPC_CLASS_NAME = result3.get(0).getClassName();
                    MainHook.log("找到 ChronosRpc: " + CHRONOS_RPC_CLASS_NAME);
                }

                List<ClassData> descriptionTextViewClassDataList = bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create().usingStrings(
                                "UgcIntroductionComponent",
                                "onLongClick DescTagSpan"
                        ))
                );
                if(descriptionTextViewClassDataList.isEmpty()) MainHook.log("DexKit fail to find descriptionTextViewClass");
               else DESCRIPTION_TEXTVIEW_CLASS_NAME = getOuterClass(bridge,descriptionTextViewClassDataList.get(0)).getName();

                List<ClassData> internationalChronosRPCClassDataList = bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create().usingStrings(
                                "EnhancedChronosPackageRunner",
                                "DestroyInputSurface: chronos engine is invalid!"
                        ))
                );

                if(descriptionTextViewClassDataList.isEmpty()) MainHook.log("DexKit fail to find internationalChronosRPCClass");
                else {
                    ClassData internationalChronosRPCClassData = getOuterClass(bridge,internationalChronosRPCClassDataList.get(0));
                    INTERNATIONAL_CHRONOS_RPC_CLASS_NAME = internationalChronosRPCClassData.getName();
                    List<MethodData> internationalInvokeMethodDataList = bridge.findMethod(FindMethod.create()
                            .searchInClass(Collections.singleton(internationalChronosRPCClassData))
                            .matcher(MethodMatcher.create()
                                    .paramCount(2)
                                    .returnType("void")
                                    .paramTypes("java.lang.Class", "kotlin.jvm.functions.Function6")
                            )
                    );
                    if(internationalInvokeMethodDataList.isEmpty()) MainHook.log("DexKit fail to find internationalInvokeMethod");
                    else INTERNATIONAL_INVOKE_METHOD_NAME = internationalInvokeMethodDataList.get(0).getName();

                }



                cacheProps.setProperty("apk_last_modified", String.valueOf(currentApkTime));
                cacheProps.setProperty("albumRecycleViewHolderClassName", albumRecycleViewHolderClassName);
                cacheProps.setProperty("publishArchiveCollectionFieldName", publishArchiveCollectionFieldName);
                cacheProps.setProperty("albumSelectPageViewClassName", albumSelectPageViewClassName);
                cacheProps.setProperty("RECYCLER_VIEW_FIELD_NAME", RECYCLER_VIEW_FIELD_NAME);
                cacheProps.setProperty("CHRONOS_RPC_CLASS_NAME", CHRONOS_RPC_CLASS_NAME);
                cacheProps.setProperty("DESCRIPTION_TEXTVIEW_CLASS_NAME",DESCRIPTION_TEXTVIEW_CLASS_NAME);
                cacheProps.setProperty("INTERNATIONAL_CHRONOS_RPC_CLASS_NAME",INTERNATIONAL_CHRONOS_RPC_CLASS_NAME);
                cacheProps.setProperty("INTERNATIONAL_INVOKE_METHOD_NAME",INTERNATIONAL_INVOKE_METHOD_NAME);



                cacheFile.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                    cacheProps.store(fos, "DexKit Obfuscation Cache for BiliBili");
                    MainHook.log(" DexKit 扫描完成，结果已持久化至缓存");
                }
            } catch (Exception e) {
                MainHook.log("❌ DexKit 扫描过程发生异常: " + e.getMessage());
            }
        }
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
            MainHook.log("error: class data list is empty");
            return;
        }
        MainHook.log("found " + dataList.size() + " class");
        for (Object data : dataList) {
            if (data instanceof ClassData) {
                MainHook.log("found class name: " + ((ClassData) data).getName() + " ");
            } else if (data instanceof MethodData) {
                MainHook.log("found method name: " + ((MethodData) data).getName() + " ");
            } else if (data instanceof FieldData) {
                MainHook.log("found field name: " + ((FieldData) data).getName() + " ");
            }
        }
    }
}