package com.weatherclimb.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.widget.RemoteViews;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

// Home-screen widget: weather now and the wind at 10, 40, 80 and 120 m next to the tower.
// The web app saves the place (WidgetBridgePlugin); Android wakes the widget every 30 min and it reads Open-Meteo itself.
public class WindWidget extends AppWidgetProvider {
    static final int[] LEVELS = {120, 80, 40, 10}; // top down, so each pill can make room under the one above

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("widget", Context.MODE_PRIVATE);
    }

    static void refreshAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, WindWidget.class));
        if (ids.length == 0) return;
        Intent i = new Intent(c, WindWidget.class);
        i.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
        c.sendBroadcast(i);
    }

    @Override
    public void onUpdate(final Context context, final AppWidgetManager manager, final int[] ids) {
        final PendingResult pending = goAsync();
        // show what we have right away, then fetch fresh numbers
        render(context, manager, ids);
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (fetch(context)) render(context, manager, ids);
                } catch (Throwable t) {
                    // no connection: the last numbers stay on screen with their time
                } finally {
                    pending.finish();
                }
            }
        }).start();
    }

    // ---------- data ----------
    static boolean fetch(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        if (!p.contains("lat")) return false;
        float lat = p.getFloat("lat", 0), lon = p.getFloat("lon", 0);
        String url = String.format(Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
            + "&current=temperature_2m,weather_code,is_day"
            + "&hourly=precipitation_probability,wind_speed_10m,wind_speed_80m,wind_speed_120m"
            + "&wind_speed_unit=ms&timezone=auto&forecast_hours=1", lat, lon);
        JSONObject j = new JSONObject(get(url));
        JSONObject cur = j.getJSONObject("current");
        JSONObject h = j.getJSONObject("hourly");
        double v10 = first(h, "wind_speed_10m", 0);
        double v80 = first(h, "wind_speed_80m", Double.NaN);
        double alpha = 0.14;
        if (!Double.isNaN(v80) && v10 > 0.3 && v80 > 0.3) alpha = Math.max(0.06, Math.min(0.4, Math.log(v80 / v10) / Math.log(8)));
        if (Double.isNaN(v80)) v80 = v10 * Math.pow(8, alpha);
        double v120 = first(h, "wind_speed_120m", v10 * Math.pow(12, alpha));
        JSONObject d = new JSONObject();
        d.put("temp", cur.optDouble("temperature_2m", 0));
        d.put("code", cur.optInt("weather_code", 3));
        d.put("day", cur.optInt("is_day", 1));
        d.put("pop", first(h, "precipitation_probability", -1));
        d.put("v10", v10);
        d.put("v40", v10 * Math.pow(4, alpha));
        d.put("v80", v80);
        d.put("v120", v120);
        d.put("at", System.currentTimeMillis());
        p.edit().putString("data", d.toString()).apply();
        return true;
    }

    static double first(JSONObject h, String k, double dflt) {
        JSONArray a = h.optJSONArray(k);
        if (a == null || a.length() == 0 || a.isNull(0)) return dflt;
        return a.optDouble(0, dflt);
    }

    static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(8000);
        try {
            if (con.getResponseCode() != 200) throw new Exception("HTTP " + con.getResponseCode());
            InputStream in = con.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }

    // ---------- drawing ----------
    static void render(Context c, AppWidgetManager manager, int[] ids) {
        SharedPreferences p = prefs(c);
        boolean ru = !"en".equals(p.getString("lang", Locale.getDefault().getLanguage().equals("ru") ? "ru" : "en"));
        boolean kmh = !"ms".equals(p.getString("unit", "kmh"));
        JSONObject d = null;
        try { if (p.contains("data")) d = new JSONObject(p.getString("data", "{}")); } catch (Exception e) { d = null; }

        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_wind);
        if (d == null) {
            v.setTextViewText(R.id.w_place, p.getString("name", "weatherClimb"));
            v.setTextViewText(R.id.w_temp, "—");
            v.setTextViewText(R.id.w_icon, "");
            v.setTextViewText(R.id.w_cond, p.contains("lat") ? (ru ? "Загружаю…" : "Loading…") : (ru ? "Откройте приложение" : "Open the app"));
            v.setTextViewText(R.id.w_pop, "");
            v.setTextViewText(R.id.w_time, "");
            v.setInt(R.id.w_root, "setBackgroundResource", R.drawable.widget_bg_day);
            v.setImageViewBitmap(R.id.w_tower, tower(c, null, kmh, ru));
        } else {
            boolean day = d.optInt("day", 1) == 1;
            int code = d.optInt("code", 3);
            long t = Math.round(d.optDouble("temp", 0));
            v.setTextViewText(R.id.w_place, p.getString("name", ""));
            v.setTextViewText(R.id.w_temp, (t > 0 ? "+" : "") + t + "°");
            v.setTextViewText(R.id.w_icon, icon(code, day));
            v.setTextViewText(R.id.w_cond, cond(code, ru));
            int pop = (int) Math.round(d.optDouble("pop", -1));
            v.setTextViewText(R.id.w_pop, pop >= 0 ? "💧 " + pop + "%" : "");
            String hhmm = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(d.optLong("at", 0)));
            v.setTextViewText(R.id.w_time, (ru ? "обновлено " : "updated ") + hhmm);
            v.setInt(R.id.w_root, "setBackgroundResource", day ? R.drawable.widget_bg_day : R.drawable.widget_bg_night);
            v.setImageViewBitmap(R.id.w_tower, tower(c, d, kmh, ru));
        }

        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // 0x04000000 = PendingIntent.FLAG_IMMUTABLE, required from Android 12
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
        v.setOnClickPendingIntent(R.id.w_root, pi);
        manager.updateAppWidget(ids, v);
    }

    // The tower photo with a pill per height, the same sqrt height scale and colours as the app.
    static Bitmap tower(Context c, JSONObject d, boolean kmh, boolean ru) {
        int W = 360, H = 300; // kept small: the picture travels to the launcher in one binder call
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        Bitmap tw = BitmapFactory.decodeResource(c.getResources(), R.drawable.widget_tower);
        float th = H - 4, twW = th * tw.getWidth() / tw.getHeight();
        float tx = W - twW - 4, ty = 2;
        cv.drawBitmap(tw, new Rect(0, 0, tw.getWidth(), tw.getHeight()), new RectF(tx, ty, tx + twW, ty + th), paint);
        tw.recycle();
        if (d == null) return bmp;

        float yTop = ty + th * 0.1f, yGround = ty + th * 0.97f;
        float right = tx + twW * 0.34f;
        Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setTextSize(24);
        big.setColor(0xff1d2a33);
        Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
        small.setTextSize(15);
        small.setColor(0xcc1d2a33);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0x33000000);

        float below = 0;
        for (int h : LEVELS) {
            double ms = d.optDouble("v" + h, 0);
            String sp = kmh ? Math.round(ms * 3.6) + (ru ? " км/ч" : " km/h")
                            : (ms < 10 ? String.format(Locale.US, "%.1f", ms) : String.valueOf(Math.round(ms))) + (ru ? " м/с" : " m/s");
            String ht = h + (ru ? "м/" : "m/") + Math.round(h * 3.28084) + "ft";
            float y = (float) (yGround - Math.sqrt(h) / Math.sqrt(120) * (yGround - yTop));
            float w = Math.max(big.measureText(sp), small.measureText(ht)) + 22, ph = 46;
            float top = Math.min(H - ph - 2, Math.max(Math.max(2, below + 5), y - ph / 2));
            below = top + ph;
            RectF r = new RectF(right - w, top, right, top + ph);
            cv.drawRoundRect(new RectF(r.left + 2, r.top + 3, r.right + 2, r.bottom + 3), 14, 14, shadow);
            fill.setColor(speedColor(ms));
            cv.drawRoundRect(r, 14, 14, fill);
            cv.drawText(sp, r.left + 11, r.top + 23, big);
            cv.drawText(ht, r.left + 11, r.top + 39, small);
        }
        return bmp;
    }

    static int speedColor(double v) {
        if (v < 2) return 0xffb8e6c9;
        if (v < 5) return 0xff8fdc8a;
        if (v < 8) return 0xffe9e36a;
        if (v < 11) return 0xfff6b84e;
        if (v < 15) return 0xfff2834a;
        return 0xffef5a5a;
    }

    static String icon(int c, boolean day) {
        if (c == 0 || c == 1) return day ? "☀️" : "🌙";
        if (c == 2) return day ? "⛅" : "☁️";
        if (c == 3) return "☁️";
        if (c == 45 || c == 48) return "🌫️";
        if (c >= 51 && c <= 67) return "🌧️";
        if (c >= 71 && c <= 77) return "🌨️";
        if (c >= 80 && c <= 82) return "🌦️";
        if (c == 85 || c == 86) return "🌨️";
        if (c >= 95) return "⛈️";
        return "☁️";
    }

    static String cond(int c, boolean ru) {
        String[] t;
        if (c == 0) t = new String[]{"Ясно", "Clear"};
        else if (c == 1) t = new String[]{"Преимущественно ясно", "Mostly clear"};
        else if (c == 2) t = new String[]{"Переменная облачность", "Partly cloudy"};
        else if (c == 3) t = new String[]{"Пасмурно", "Overcast"};
        else if (c == 45 || c == 48) t = new String[]{"Туман", "Fog"};
        else if (c >= 51 && c <= 57) t = new String[]{"Морось", "Drizzle"};
        else if (c == 61) t = new String[]{"Небольшой дождь", "Light rain"};
        else if (c == 63) t = new String[]{"Дождь", "Rain"};
        else if (c == 65) t = new String[]{"Сильный дождь", "Heavy rain"};
        else if (c == 66 || c == 67) t = new String[]{"Ледяной дождь", "Freezing rain"};
        else if (c == 71) t = new String[]{"Небольшой снег", "Light snow"};
        else if (c == 73) t = new String[]{"Снег", "Snow"};
        else if (c == 75) t = new String[]{"Сильный снег", "Heavy snow"};
        else if (c == 77) t = new String[]{"Снежная крупа", "Snow grains"};
        else if (c >= 80 && c <= 82) t = c == 82 ? new String[]{"Сильный ливень", "Violent showers"} : new String[]{"Ливень", "Showers"};
        else if (c == 85 || c == 86) t = new String[]{"Снегопад", "Snow showers"};
        else if (c == 95) t = new String[]{"Гроза", "Thunderstorm"};
        else if (c == 96 || c == 99) t = new String[]{"Гроза с градом", "Thunderstorm with hail"};
        else t = new String[]{"Облачно", "Cloudy"};
        return ru ? t[0] : t[1];
    }
}
