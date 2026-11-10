package io.github.fredoseep.bilidanmucheck;

import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public class PausePopUpSniper {
    private static final String TAG = "PausePopUpSniper";

    public static void hook(ClassLoader classLoader,DexKitHelper helper){
        Log.d(TAG,"injected");
        try{
            XposedHelpers.findAndHookMethod("com.bilibili.ship.theseus.united.page.pausedpage.PausedPageService", classLoader, helper.IS_POP_UP_AD_VALID_METHOD_NAME, "kotlinx.coroutines.Job", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    Log.d(TAG,"original return value is "+param.getResult());
                    param.setResult(false);
                }
            });
        } catch (Exception e) {
            Log.e(TAG,"Error1: "+e.getMessage());
        }
    }
}
