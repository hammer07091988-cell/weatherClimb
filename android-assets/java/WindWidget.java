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
import android.os.Bundle;
import android.util.TypedValue;
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

// Home-screen widgets: weather now and the wind by height. One provider per size (see the subclasses below);
// each draws itself to the size the launcher actually gives it, so text and the tower fill the widget.
// The web app saves the place (WidgetBridgePlugin); Android wakes the widgets every 30 min and they read Open-Meteo.
public class WindWidget extends AppWidgetProvider {
    static final int MAIN = 0, MINI = 1, SMALL = 2, SQUARE = 3, WIDE = 4, BIG = 5;
    static final Class<?>[] ALL = {WindWidget.class, WidgetMini.class, WidgetSmall.class, WidgetSquare.class, WidgetWide.class, WidgetBig.class};
    static final long FRESH_MS = 5 * 60 * 1000;

    int kind() { return MAIN; }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("widget", Context.MODE_PRIVATE);
    }

    static void refreshAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (Class<?> k : ALL) {
            int[] ids = m.getAppWidgetIds(new ComponentName(c, k));
            if (ids.length == 0) continue;
            Intent i = new Intent(c, k);
            i.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
            c.sendBroadcast(i);
        }
    }

    @Override
    public void onUpdate(final Context context, final AppWidgetManager manager, final int[] ids) {
        final PendingResult pending = goAsync();
        // show what we have right away, then fetch fresh numbers
        for (int id : ids) render(context, manager, id);
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (fetch(context)) for (int id : ids) render(context, manager, id);
                } catch (Throwable t) {
                    // no connection: the last numbers stay on screen with their time
                } finally {
                    pending.finish();
                }
            }
        }).start();
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        render(context, manager, id); // resized
    }

    // ---------- data ----------
    static boolean fetch(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        if (!p.contains("lat")) return false;
        try {
            // several widgets wake together: one download is enough
            if (System.currentTimeMillis() - new JSONObject(p.getString("data", "{}")).optLong("at", 0) < FRESH_MS) return true;
        } catch (Exception e) { /* fetch below */ }
        float lat = p.getFloat("lat", 0), lon = p.getFloat("lon", 0);
        String url = String.format(Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
            + "&current=temperature_2m,weather_code,is_day"
            + "&hourly=temperature_2m,weather_code,is_day,precipitation_probability,wind_speed_10m,wind_speed_80m,wind_speed_120m"
            + "&wind_speed_unit=ms&timezone=auto&forecast_hours=7", lat, lon);
        JSONObject j = new JSONObject(get(url));
        JSONObject cur = j.getJSONObject("current");
        JSONObject h = j.getJSONObject("hourly");
        double[] w = profile(h, 0);
        JSONObject d = new JSONObject();
        d.put("temp", cur.optDouble("temperature_2m", 0));
        d.put("code", cur.optInt("weather_code", 3));
        d.put("day", cur.optInt("is_day", 1));
        d.put("pop", at(h, "precipitation_probability", 0, -1));
        d.put("v10", w[0]);
        d.put("v40", w[1]);
        d.put("v80", w[2]);
        d.put("v120", w[3]);
        JSONArray hours = new JSONArray();
        JSONArray times = h.optJSONArray("time");
        for (int i = 1; times != null && i < times.length(); i++) {
            double[] wi = profile(h, i);
            JSONObject o = new JSONObject();
            o.put("t", times.getString(i).substring(11, 16));
            o.put("code", (int) at(h, "weather_code", i, 3));
            o.put("day", (int) at(h, "is_day", i, 1));
            o.put("temp", at(h, "temperature_2m", i, 0));
            o.put("v10", wi[0]);
            o.put("v120", wi[3]);
            hours.put(o);
        }
        d.put("hours", hours);
        d.put("at", System.currentTimeMillis());
        p.edit().putString("data", d.toString()).apply();
        return true;
    }

    // wind at 10, 40, 80 and 120 m, filling the gaps with the same power law as the app
    static double[] profile(JSONObject h, int i) {
        double v10 = at(h, "wind_speed_10m", i, 0);
        double v80 = at(h, "wind_speed_80m", i, Double.NaN);
        double alpha = 0.14;
        if (!Double.isNaN(v80) && v10 > 0.3 && v80 > 0.3) alpha = Math.max(0.06, Math.min(0.4, Math.log(v80 / v10) / Math.log(8)));
        if (Double.isNaN(v80)) v80 = v10 * Math.pow(8, alpha);
        double v120 = at(h, "wind_speed_120m", i, v10 * Math.pow(12, alpha));
        return new double[]{v10, v10 * Math.pow(4, alpha), v80, v120};
    }

    static double at(JSONObject h, String k, int i, double dflt) {
        JSONArray a = h.optJSONArray(k);
        if (a == null || a.length() <= i || a.isNull(i)) return dflt;
        return a.optDouble(i, dflt);
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

    // ---------- layout ----------
    static int layoutOf(int kind) {
        switch (kind) {
            case MINI: return R.layout.widget_mini;
            case SMALL: return R.layout.widget_small;
            case WIDE: return R.layout.widget_wide;
            case SQUARE: return R.layout.widget_square;
            case BIG: return R.layout.widget_big;
            default: return R.layout.widget_wind;
        }
    }

    // default sizes (dp) for launchers that do not report one
    static final int[][] SIZE = {{250, 110}, {40, 40}, {110, 40}, {110, 110}, {250, 40}, {250, 180}};

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    void render(Context c, AppWidgetManager manager, int id) {
        int kind = kind();
        Bundle o = manager.getAppWidgetOptions(id);
        // portrait: the width is the smallest and the height the largest the launcher may use
        float w = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
        float h = o == null ? 0 : o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
        if (w < 30 || h < 30) { w = SIZE[kind][0]; h = SIZE[kind][1]; }

        SharedPreferences p = prefs(c);
        boolean ru = !"en".equals(p.getString("lang", Locale.getDefault().getLanguage().equals("ru") ? "ru" : "en"));
        boolean kmh = !"ms".equals(p.getString("unit", "kmh"));
        JSONObject d = null;
        try { if (p.contains("data")) d = new JSONObject(p.getString("data", "{}")); } catch (Exception e) { d = null; }

        RemoteViews v = new RemoteViews(c.getPackageName(), layoutOf(kind));
        boolean day = d == null || d.optInt("day", 1) == 1;
        v.setInt(R.id.w_root, "setBackgroundResource", day ? R.drawable.widget_bg_day : R.drawable.widget_bg_night);

        String place = p.getString("name", "weatherClimb"), temp = "—", icon = "", cond, pop = "", time = "";
        if (d == null) {
            cond = p.contains("lat") ? (ru ? "Загружаю…" : "Loading…") : (ru ? "Откройте приложение" : "Open the app");
        } else {
            int code = d.optInt("code", 3);
            temp = deg(d.optDouble("temp", 0));
            icon = icon(code, day);
            cond = cond(code, ru);
            int pp = (int) Math.round(d.optDouble("pop", -1));
            pop = pp >= 0 ? "💧 " + pp + "%" : "";
            time = (ru ? "обновлено " : "updated ")
                + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(d.optLong("at", 0)));
        }

        // text sizes follow the widget's size; picture areas match the weights in the layouts
        if (kind == MINI) {
            float iw = w - 12, ih = (h - 12) / 2;
            size(v, R.id.w_temp, clamp(Math.min((h - 12) * 0.26f, iw * 0.3f), 13, 34));
            size(v, R.id.w_icon, clamp(Math.min((h - 12) * 0.2f, iw * 0.22f), 11, 26));
            v.setTextViewText(R.id.w_temp, temp);
            v.setTextViewText(R.id.w_icon, icon);
            v.setImageViewBitmap(R.id.w_pills, pillColumn(d, new int[]{120}, iw, ih, kmh, ru));
        } else if (kind == SQUARE) {
            float inner = h - 16, top = inner * 0.3f;
            float t = clamp(Math.min(top * 0.75f, (w - 16) * 0.2f), 16, 44);
            size(v, R.id.w_temp, t);
            size(v, R.id.w_icon, t * 0.7f);
            size(v, R.id.w_pop, clamp(t * 0.4f, 10, 16));
            v.setTextViewText(R.id.w_temp, temp);
            v.setTextViewText(R.id.w_icon, icon);
            v.setTextViewText(R.id.w_pop, d == null ? cond : pop);
            v.setImageViewBitmap(R.id.w_tower, tower(c, d, w - 16, inner - top, kmh, ru));
        } else if (kind == SMALL || kind == WIDE) {
            boolean wide = kind == WIDE;
            float inner = h - 16, leftW = (w - 16) / (wide ? 3.4f : 2f), iw = (w - 16) - leftW;
            float t = clamp(Math.min(inner * (wide ? 0.42f : 0.38f), leftW * 0.3f), 15, 44);
            size(v, R.id.w_place, clamp(Math.min(inner * 0.14f, t * 0.42f), 9, 15));
            size(v, R.id.w_temp, t);
            size(v, R.id.w_icon, t * 0.7f);
            size(v, R.id.w_pop, clamp(Math.min(inner * 0.14f, t * 0.42f), 9, 15));
            v.setTextViewText(R.id.w_place, place);
            v.setTextViewText(R.id.w_temp, temp);
            v.setTextViewText(R.id.w_icon, icon);
            v.setTextViewText(R.id.w_pop, d == null ? cond : wide ? cond + (pop.isEmpty() ? "" : "   " + pop) : pop);
            v.setImageViewBitmap(R.id.w_pills, wide ? pillRow(d, iw, inner, kmh, ru) : pillColumn(d, new int[]{120, 10}, iw, inner, kmh, ru));
        } else {
            float inner = h - 20, top = kind == BIG ? inner * 1.7f / 2.7f : inner;
            float leftW = (w - 20) / 2.15f, iw = (w - 20) - leftW;
            float t = clamp(Math.min(top * 0.3f, leftW * 0.3f), 20, 64), s = clamp(Math.min(top * 0.1f, leftW * 0.1f), 10, 18);
            size(v, R.id.w_place, s);
            size(v, R.id.w_temp, t);
            size(v, R.id.w_icon, t * 0.68f);
            size(v, R.id.w_cond, s);
            size(v, R.id.w_pop, s);
            size(v, R.id.w_time, Math.max(9, s * 0.75f));
            v.setTextViewText(R.id.w_place, place);
            v.setTextViewText(R.id.w_temp, temp);
            v.setTextViewText(R.id.w_icon, icon);
            v.setTextViewText(R.id.w_cond, cond);
            v.setTextViewText(R.id.w_pop, pop);
            v.setTextViewText(R.id.w_time, time);
            v.setImageViewBitmap(R.id.w_tower, tower(c, d, iw, top, kmh, ru));
            if (kind == BIG) v.setImageViewBitmap(R.id.w_hours, hours(d, w - 20, inner - top, kmh, ru));
        }

        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // 0x04000000 = PendingIntent.FLAG_IMMUTABLE, required from Android 12
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
        v.setOnClickPendingIntent(R.id.w_root, pi);
        manager.updateAppWidget(id, v);
    }

    static void size(RemoteViews v, int id, float dp) {
        v.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_DIP, dp);
    }

    static String deg(double t) {
        long r = Math.round(t);
        return (r > 0 ? "+" : "") + r + "°";
    }

    // ---------- pictures ----------
    // A bitmap of wDp x hDp. Pixels per dp are capped so the picture fits in one binder call to the launcher.
    static Bitmap canvasFor(float wDp, float hDp) {
        float s = (float) Math.min(2.5, Math.sqrt(140000.0 / Math.max(1, wDp * hDp)));
        return Bitmap.createBitmap(Math.max(8, Math.round(wDp * s)), Math.max(8, Math.round(hDp * s)), Bitmap.Config.ARGB_8888);
    }

    static String speed(double ms, boolean kmh, boolean ru, boolean unit) {
        String n = kmh ? String.valueOf(Math.round(ms * 3.6)) : ms < 10 ? String.format(Locale.US, "%.1f", ms) : String.valueOf(Math.round(ms));
        return unit ? n + (kmh ? (ru ? " км/ч" : " km/h") : (ru ? " м/с" : " m/s")) : n;
    }

    static String height(int h, boolean ru) {
        return h + (ru ? "м/" : "m/") + Math.round(h * 3.28084) + "ft";
    }

    static Paint text(float size, boolean bold, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    // width a pill of height ph needs for its two lines
    static float pillWidth(float ph, String a, String b) {
        return Math.max(text(ph * 0.5f, true, 0).measureText(a), text(ph * 0.32f, false, 0).measureText(b)) + ph * 0.46f;
    }

    // a rounded pill coloured by the wind, speed on top and the height below, like in the app
    static void pill(Canvas cv, RectF r, double ms, String a, String b) {
        float ph = r.height(), rad = ph * 0.3f;
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0x33000000);
        cv.drawRoundRect(new RectF(r.left + ph * 0.04f, r.top + ph * 0.06f, r.right + ph * 0.04f, r.bottom + ph * 0.06f), rad, rad, shadow);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(speedColor(ms));
        cv.drawRoundRect(r, rad, rad, fill);
        Paint big = text(ph * 0.5f, true, 0xff1d2a33), small = text(ph * 0.32f, false, 0xcc1d2a33);
        float fit = Math.min(1f, (r.width() - ph * 0.3f) / Math.max(1, Math.max(big.measureText(a), small.measureText(b))));
        big.setTextSize(big.getTextSize() * fit);
        small.setTextSize(small.getTextSize() * fit);
        float cx = r.centerX();
        cv.drawText(a, cx - big.measureText(a) / 2, r.top + ph * 0.5f, big);
        cv.drawText(b, cx - small.measureText(b) / 2, r.top + ph * 0.84f, small);
    }

    // pills stacked top to bottom (1x1 and 2x1)
    static Bitmap pillColumn(JSONObject d, int[] levels, float wDp, float hDp, boolean kmh, boolean ru) {
        Bitmap bmp = canvasFor(wDp, hDp);
        if (d == null) return bmp;
        Canvas cv = new Canvas(bmp);
        float W = bmp.getWidth(), H = bmp.getHeight(), gap = H * 0.05f;
        float ph = Math.min((H - gap * (levels.length + 1)) / levels.length, W / 2.1f);
        float y = (H - ph * levels.length - gap * (levels.length - 1)) / 2;
        for (int h : levels) {
            double ms = d.optDouble("v" + h, 0);
            String a = speed(ms, kmh, ru, true), b = height(h, ru);
            float pw = Math.min(W - 4, Math.max(pillWidth(ph, a, b), W * 0.7f));
            pill(cv, new RectF((W - pw) / 2, y, (W + pw) / 2, y + ph), ms, a, b);
            y += ph + gap;
        }
        return bmp;
    }

    // four pills side by side, 10 m on the left up to 120 m on the right (4x1)
    static Bitmap pillRow(JSONObject d, float wDp, float hDp, boolean kmh, boolean ru) {
        Bitmap bmp = canvasFor(wDp, hDp);
        if (d == null) return bmp;
        Canvas cv = new Canvas(bmp);
        float W = bmp.getWidth(), H = bmp.getHeight(), cell = W / 4, gap = cell * 0.06f;
        float ph = Math.min(H * 0.8f, (cell - gap) / 1.9f);
        int[] levels = {10, 40, 80, 120};
        for (int i = 0; i < 4; i++) {
            double ms = d.optDouble("v" + levels[i], 0);
            RectF r = new RectF(i * cell + gap / 2, (H - ph) / 2, (i + 1) * cell - gap / 2, (H + ph) / 2);
            pill(cv, r, ms, speed(ms, kmh, ru, true), height(levels[i], ru));
        }
        return bmp;
    }

    // The tower photo with a pill per height, the same sqrt height scale and colours as the app.
    static Bitmap tower(Context c, JSONObject d, float wDp, float hDp, boolean kmh, boolean ru) {
        Bitmap bmp = canvasFor(wDp, hDp);
        Canvas cv = new Canvas(bmp);
        float W = bmp.getWidth(), H = bmp.getHeight();
        Bitmap tw = BitmapFactory.decodeResource(c.getResources(), R.drawable.widget_tower);
        float th = H * 0.98f, twW = th * tw.getWidth() / tw.getHeight();
        if (twW > W * 0.45f) { twW = W * 0.45f; th = twW * tw.getHeight() / tw.getWidth(); }
        float tx = W - twW - H * 0.01f, ty = H - th - H * 0.01f;
        int[] levels = {120, 80, 40, 10}; // top down, so each pill can make room under the one above
        float yTop = ty + th * 0.1f, yGround = ty + th * 0.97f, right = tx + twW * 0.34f;
        // pills as tall as four fit beside the tower, narrowed if the space left of the tower is tight
        float ph = Math.min(H * 0.2f, (yGround - yTop) / 3.6f);
        if (d != null) {
            float need = 0;
            for (int h : levels) need = Math.max(need, pillWidth(ph, speed(d.optDouble("v" + h, 0), kmh, ru, true), height(h, ru)));
            if (need > right - 2) ph *= (right - 2) / need;
            else {
                // room to spare: centre the tower and its pills in the picture
                float shift = (right - need - (W - tx - twW)) / 2;
                tx -= shift;
                right -= shift;
            }
        }
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        cv.drawBitmap(tw, new Rect(0, 0, tw.getWidth(), tw.getHeight()), new RectF(tx, ty, tx + twW, ty + th), paint);
        tw.recycle();
        if (d == null) return bmp;

        float below = 0;
        for (int h : levels) {
            double ms = d.optDouble("v" + h, 0);
            String a = speed(ms, kmh, ru, true), b = height(h, ru);
            float y = (float) (yGround - Math.sqrt(h) / Math.sqrt(120) * (yGround - yTop));
            float pw = pillWidth(ph, a, b);
            float top = Math.min(H - ph - 2, Math.max(Math.max(2, below + ph * 0.1f), y - ph / 2));
            below = top + ph;
            pill(cv, new RectF(right - pw, top, right, top + ph), ms, a, b);
        }
        return bmp;
    }

    // next six hours: time, sky, temperature and the wind at 120 and 10 m (4x3)
    static Bitmap hours(JSONObject d, float wDp, float hDp, boolean kmh, boolean ru) {
        Bitmap bmp = canvasFor(wDp, hDp);
        JSONArray hs = d == null ? null : d.optJSONArray("hours");
        if (hs == null || hs.length() == 0) return bmp;
        Canvas cv = new Canvas(bmp);
        float W = bmp.getWidth(), H = bmp.getHeight();
        int n = Math.min(6, hs.length());
        float labelW = W * 0.14f, col = (W - labelW) / n;
        float[] row = {0.15f, 0.36f, 0.55f, 0.76f, 0.95f}; // baselines as a share of the height
        float fs = Math.min(H * 0.14f, col * 0.3f);
        Paint white = text(fs, false, 0xffffffff), bold = text(fs * 1.05f, true, 0xffffffff), emoji = text(fs * 1.25f, false, 0xffffffff);
        white.setShadowLayer(fs * 0.15f, 0, fs * 0.06f, 0x80000000);
        bold.setShadowLayer(fs * 0.15f, 0, fs * 0.06f, 0x80000000);
        Paint label = text(fs * 0.8f, true, 0xddffffff);
        label.setShadowLayer(fs * 0.15f, 0, fs * 0.06f, 0x80000000);
        cv.drawLine(0, 1, W, 1, text(1, false, 0x40ffffff));
        cv.drawText(kmh ? (ru ? "км/ч" : "km/h") : (ru ? "м/с" : "m/s"), 0, H * row[0], label);
        cv.drawText(ru ? "120м" : "120m", 0, H * row[3] - fs * 0.15f, label);
        cv.drawText(ru ? "10м" : "10m", 0, H * row[4] - fs * 0.15f, label);
        float chipH = Math.min(H * 0.17f, col * 0.5f), chipW = Math.min(col * 0.82f, chipH * 2.2f);
        Paint chipText = text(chipH * 0.62f, true, 0xff1d2a33);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < n; i++) {
            JSONObject o = hs.optJSONObject(i);
            float cx = labelW + col * (i + 0.5f);
            centered(cv, o.optString("t"), cx, H * row[0], white);
            centered(cv, icon(o.optInt("code", 3), o.optInt("day", 1) == 1), cx, H * row[1], emoji);
            centered(cv, deg(o.optDouble("temp", 0)), cx, H * row[2], bold);
            String[] keys = {"v120", "v10"};
            for (int k = 0; k < 2; k++) {
                double ms = o.optDouble(keys[k], 0);
                float base = H * row[3 + k];
                RectF r = new RectF(cx - chipW / 2, base - chipH * 0.85f, cx + chipW / 2, base + chipH * 0.15f);
                fill.setColor(speedColor(ms));
                cv.drawRoundRect(r, chipH * 0.35f, chipH * 0.35f, fill);
                centered(cv, speed(ms, kmh, ru, false), cx, r.top + chipH * 0.72f, chipText);
            }
        }
        return bmp;
    }

    static void centered(Canvas cv, String s, float cx, float y, Paint p) {
        cv.drawText(s, cx - p.measureText(s) / 2, y, p);
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
