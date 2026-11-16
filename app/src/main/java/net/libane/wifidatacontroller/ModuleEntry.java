package net.libane.wifidatacontroller;

import android.util.Log;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Vector module entry. In system_server it turns mobile data off when Wi-Fi
 * connects and back on when Wi-Fi disconnects.
 */
public final class ModuleEntry extends XposedModule {
    private static final String TAG = "WiFiDataController";
    private final WifiDataSwitcher switcher = new WifiDataSwitcher();

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        if (param.isSystemServer()) switcher.start(this::xposedLog);
        log(Log.INFO, TAG, "Module loaded. process=" + param.getProcessName()
                + " systemServer=" + param.isSystemServer()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion());
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        log(Log.INFO, TAG, "System server is starting; arming the Wi-Fi mobile-data switch.");
        switcher.start(this::xposedLog);
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if ("android".equals(param.getPackageName())) {
            log(Log.INFO, TAG, "android package loaded; arming the Wi-Fi mobile-data switch.");
            switcher.start(this::xposedLog);
        }
    }

    private void xposedLog(int priority, String message, Throwable error) {
        if (error != null) log(priority, TAG, message, error);
        else log(priority, TAG, message);
    }
}
