package io.github.fredoseep.bilidanmucheck.sniffer;

import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public class IgHttpEngineHook {
    private static final String TAG = "IgHttpEngineHook";

    public static void hook(ClassLoader classLoader){

        try {
            XposedHelpers.findAndHookMethod(
                    "com.bilibili.lib.dd.DeviceDecision",
                    classLoader,
                    "getBoolean",
                    String.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String key = (String) param.args[0];

                            if ("ighttp_check_certs_enable".equals(key)
                                    || "ignet_grpc_check_certs_enable".equals(key)) {
                                param.setResult(false);
                                Log.i(TAG, key + " -> false");
                            }
                        }
                    }
            );

            Log.i(TAG, "Certificate config hook installed");
        } catch (Throwable e) {
            Log.e(TAG, "Certificate config hook failed", e);
        }
    }
}
