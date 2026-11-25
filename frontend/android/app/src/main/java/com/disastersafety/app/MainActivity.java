package com.disastersafety.app;

import android.os.Bundle;
import android.webkit.WebSettings;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NativeBridgeTestPlugin.class);
        registerPlugin(BleChatPlugin.class);
        super.onCreate(savedInstanceState);

        // Ensure WebView geolocation is explicitly enabled for GPS Location & Map screens
        if (this.bridge != null && this.bridge.getWebView() != null) {
            WebSettings settings = this.bridge.getWebView().getSettings();
            settings.setGeolocationEnabled(true);
        }
    }
}
