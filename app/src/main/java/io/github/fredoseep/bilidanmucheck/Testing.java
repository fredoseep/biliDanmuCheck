package io.github.fredoseep.bilidanmucheck;

import android.util.Log;

import java.util.Arrays;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public class Testing {
    private static final String TAG = "DanmuBlockTesting";
    public static void hook(ClassLoader classLoader){
        try {
            XposedHelpers.findAndHookMethod("ht1.d", classLoader, "c", "android.content.Context", "java.lang.String", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    Log.d(TAG,"c method second param is :"+ param.args[1]);
                    Log.d(TAG, " c method called ,stack trace: "+ Arrays.toString(new Throwable().getStackTrace()));
                }
            });
        } catch (Exception e) {
            Log.e(TAG,"ht1.d,c method hooked Error: "+e.getMessage());
        }
        try{
            XposedHelpers.findAndHookMethod("ht1.d", classLoader, "a", "android.content.Context", "java.util.Collection", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    Log.d(TAG,"ht1.d called ,stackTrace: "+ Arrays.toString(new Throwable().getStackTrace()));
                }
            });
        } catch (Exception e) {
            Log.e(TAG,"Exception at ht1.d hook: "+e.getMessage());
        }

    }
}
