package com.weatherclimb.app;

import android.content.SharedPreferences;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

// Called from index.html every time a place is loaded: the widget keeps showing that place.
@CapacitorPlugin(name = "WidgetBridge")
public class WidgetBridgePlugin extends Plugin {
    @PluginMethod
    public void setPlace(PluginCall call) {
        Double lat = call.getDouble("lat"), lon = call.getDouble("lon");
        if (lat == null || lon == null) { call.reject("lat and lon are required"); return; }
        String name = call.getString("name", "");
        String lang = call.getString("lang", "ru");
        String unit = call.getString("unit", "kmh");
        String terrain = call.getString("terrain", "field");
        SharedPreferences p = WindWidget.prefs(getContext());
        boolean moved = Math.abs(p.getFloat("lat", 999f) - lat) > 0.005 || Math.abs(p.getFloat("lon", 999f) - lon) > 0.005;
        SharedPreferences.Editor e = p.edit()
            .putFloat("lat", lat.floatValue()).putFloat("lon", lon.floatValue())
            .putString("name", name).putString("lang", lang).putString("unit", unit).putString("terrain", terrain);
        if (moved) e.remove("data");
        e.apply();
        WindWidget.refreshAll(getContext());
        call.resolve();
    }
}
