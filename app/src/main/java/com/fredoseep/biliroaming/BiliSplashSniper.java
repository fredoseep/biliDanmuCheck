package com.fredoseep.biliroaming;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedBridge;

public class BiliSplashSniper {

    public static void hook(ClassLoader classLoader, DexKitHelper helper) {
        try {
            XposedHelpers.findAndHookMethod(
                    helper.BASE_SPLASH_CLASS_NAME,
                    classLoader,
                    helper.SPLASH_READY_METHOD_NAME,
                    boolean.class, // k8 方法的参数 boolean z12
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object baseSplashInstance = param.thisObject;

                            XposedBridge.log("BiliSplashSniper: BaseSplash is ready, triggering skip immediately.");

                            try {
                                XposedHelpers.callMethod(baseSplashInstance, helper.SPLASH_SKIP_METHOD_NAME);
                            } catch (Throwable e) {
                                XposedBridge.log("BiliSplashSniper: Failed to call Sf(), falling back to Nf().");
                                XposedHelpers.callMethod(baseSplashInstance, "Nf", null, null, false);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log("BiliSplashSniper: Hook failed for " + helper.BASE_SPLASH_CLASS_NAME + " -> " + t.getMessage());
        }
    }
}