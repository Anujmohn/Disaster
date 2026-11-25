package com.disastersafety.app;

import android.os.Build;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "NativeBridgeTest")
public class NativeBridgeTestPlugin extends Plugin {

    @PluginMethod
    public void getNativeStatus(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("status", "connected");
        ret.put("platform", "Android");
        ret.put("androidVersion", Build.VERSION.RELEASE);
        ret.put("sdkInt", Build.VERSION.SDK_INT);
        ret.put("deviceModel", Build.MODEL);
        ret.put("message", "Capacitor Native Android Bridge is functional.");
        call.resolve(ret);
    }
}
