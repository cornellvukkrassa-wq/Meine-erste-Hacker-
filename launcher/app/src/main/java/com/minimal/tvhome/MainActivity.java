package com.minimal.tvhome;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal Apple TV-style home screen: rotating wallpaper, clock, a dock of
 * favourite apps and an "All apps" overlay. Configure from adb:
 *   am start -n com.minimal.tvhome/.MainActivity --es favorites "pkg1,pkg2"
 *   am start -n com.minimal.tvhome/.MainActivity --ei interval 90
 *   am start -n com.minimal.tvhome/.MainActivity --ez reload true
 * Long-press OK on a tile toggles it in the dock.
 */
public class MainActivity extends Activity {
    private static final String[] DEFAULT_FAVORITES = {
            "com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus", "com.spotify.tv.android", "com.plexapp.android", "org.xbmc.kodi"};
    // Fallback "wallpapers" when no photos were pushed: dusk, forest, ocean, alpine.
    private static final int[][] GRADIENTS = {
            {0xFF0F2027, 0xFF203A43, 0xFF2C5364}, {0xFF0B3D2E, 0xFF1D5C3A, 0xFF4A7C59},
            {0xFF0A2342, 0xFF126872, 0xFF2CA58D}, {0xFF283E51, 0xFF4B79A1, 0xFF8FB3D9}};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private ImageView[] bg = new ImageView[2];
    private int front = 0;
    private final ObjectAnimator[] drift = new ObjectAnimator[2];
    private LinearLayout dock;
    private TextView greeting;
    private FrameLayout drawer;
    private GridLayout grid;
    private List<File> walls = new ArrayList<>();
    private int wallIndex = -1;
    private float density;

    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            nextWallpaper();
            handler.postDelayed(this, prefs.getInt("interval", 60) * 1000L);
        }
    };

    static final class App {
        final String pkg; final String label; final Drawable banner; final Drawable icon;
        App(String pkg, String label, Drawable banner, Drawable icon) {
            this.pkg = pkg; this.label = label; this.banner = banner; this.icon = icon;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        setContentView(buildUi());
        handleIntent(getIntent());
        loadWallpaperList();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        closeDrawer(); // Home pressed while we are in front
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (greeting != null) greeting.setText(greetingText());
        buildDock();
        handler.removeCallbacks(rotate);
        handler.post(rotate);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotate);
    }

    @Override
    public void onBackPressed() {
        closeDrawer(); // A launcher never finishes on Back.
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        SharedPreferences.Editor e = prefs.edit();
        if (i.hasExtra("favorites")) e.putString("favorites", i.getStringExtra("favorites"));
        if (i.hasExtra("interval")) e.putInt("interval", Math.max(10, i.getIntExtra("interval", 60)));
        e.apply();
        if (i.getBooleanExtra("reload", false)) loadWallpaperList();
        i.removeExtra("favorites");
        i.removeExtra("interval");
        i.removeExtra("reload");
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) { return Math.round(v * density); }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        for (int k = 0; k < 2; k++) {
            bg[k] = new ImageView(this);
            bg[k].setScaleType(ImageView.ScaleType.CENTER_CROP);
            bg[k].setAlpha(k == 0 ? 1f : 0f);
            root.addView(bg[k], match());
        }

        // Darker, moodier background - a flat dim plus a stronger vignette top and bottom.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x88000000, 0x44000000, 0x55000000, 0xCC000000}));
        root.addView(scrim, match());

        // Top-right: time, with the weekday and date quietly underneath.
        LinearLayout clockCol = new LinearLayout(this);
        clockCol.setOrientation(LinearLayout.VERTICAL);
        clockCol.setGravity(Gravity.END);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(34);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setShadowLayer(12, 0, 2, 0x88000000);
        clock.setGravity(Gravity.END);
        clockCol.addView(clock);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEE, d. MMM");
        date.setFormat24Hour("EEE, d. MMM");
        date.setTextColor(0xCCFFFFFF);
        date.setTextSize(15);
        date.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        date.setShadowLayer(8, 0, 1, 0x88000000);
        date.setGravity(Gravity.END);
        clockCol.addView(date);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        clp.setMargins(0, dp(28), dp(48), 0);
        root.addView(clockCol, clp);

        // Top-left: a greeting that follows the time of day - a bit of warmth.
        greeting = new TextView(this);
        greeting.setTextColor(Color.WHITE);
        greeting.setTextSize(30);
        greeting.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        greeting.setShadowLayer(12, 0, 2, 0x88000000);
        greeting.setText(greetingText());
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        glp.setMargins(dp(48), dp(30), 0, 0);
        root.addView(greeting, glp);

        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(18), dp(18), dp(18), dp(18));
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0x40FFFFFF);
        glass.setCornerRadius(dp(26));
        dock.setBackground(glass);
        HorizontalScrollView dockScroll = new HorizontalScrollView(this);
        dockScroll.setHorizontalScrollBarEnabled(false);
        dockScroll.setClipChildren(false);
        dockScroll.addView(dock);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dlp.setMargins(dp(48), 0, dp(48), dp(40));
        root.addView(dockScroll, dlp);

        drawer = new FrameLayout(this);
        drawer.setBackgroundColor(0xE0101010);
        drawer.setVisibility(View.GONE);
        ScrollView scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        grid = new GridLayout(this);
        grid.setColumnCount(5);
        grid.setClipChildren(false);
        grid.setPadding(dp(56), dp(56), dp(56), dp(56));
        scroll.addView(grid);
        drawer.addView(scroll, match());
        root.addView(drawer, match());
        return root;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private String greetingText() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if (h < 5) return "Gute Nacht";
        if (h < 11) return "Guten Morgen";
        if (h < 17) return "Guten Tag";
        if (h < 22) return "Guten Abend";
        return "Gute Nacht";
    }

    private View tile(final App app, final boolean inDock) {
        FrameLayout t = new FrameLayout(this);
        t.setFocusable(true);
        t.setClipToOutline(true);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(14));
        shape.setColor(0xFF2A2A2E);
        t.setBackground(shape);
        if (app.banner != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(app.banner);
            t.addView(iv, match());
        } else {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            if (app.icon != null) {
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(app.icon);
                col.addView(iv, new LinearLayout.LayoutParams(dp(48), dp(48)));
            }
            TextView tv = new TextView(this);
            tv.setText(app.label);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(13);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(8), dp(6), dp(8), 0);
            col.addView(tv);
            t.addView(col, match());
        }
        t.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.12f : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(10) : 0).setDuration(150).start();
        });
        t.setOnClickListener(v -> launch(app.pkg));
        t.setOnLongClickListener(v -> {
            toggleFavorite(app, inDock);
            return true;
        });
        return t;
    }

    private LinearLayout.LayoutParams dockTileParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(200), dp(112));
        lp.setMargins(dp(10), 0, dp(10), 0);
        return lp;
    }

    private void buildDock() {
        dock.removeAllViews();
        Map<String, App> apps = loadApps();
        for (String pkg : favorites()) {
            App a = apps.get(pkg);
            if (a != null) dock.addView(tile(a, true), dockTileParams());
        }
        App all = new App("", "All apps", null, getDrawable(android.R.drawable.ic_menu_sort_by_size));
        View allTile = tile(all, true);
        allTile.setOnClickListener(v -> openDrawer());
        allTile.setOnLongClickListener(null);
        dock.addView(allTile, dockTileParams());
        if (drawer.getVisibility() != View.VISIBLE) dock.getChildAt(0).requestFocus();
    }

    private void openDrawer() {
        grid.removeAllViews();
        for (App a : loadApps().values()) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(200);
            lp.height = dp(112);
            lp.setMargins(dp(12), dp(12), dp(12), dp(12));
            grid.addView(tile(a, false), lp);
        }
        drawer.setVisibility(View.VISIBLE);
        if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
    }

    private void closeDrawer() {
        if (drawer != null && drawer.getVisibility() == View.VISIBLE) {
            drawer.setVisibility(View.GONE);
            buildDock();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && drawer.getVisibility() != View.VISIBLE) {
            openDrawer();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- apps

    private List<String> favorites() {
        String s = prefs.getString("favorites", null);
        if (s != null) {
            List<String> out = new ArrayList<>();
            for (String p : s.split(",")) if (!p.trim().isEmpty()) out.add(p.trim());
            return out;
        }
        return Arrays.asList(DEFAULT_FAVORITES);
    }

    private void toggleFavorite(App app, boolean inDock) {
        List<String> favs = new ArrayList<>(favorites());
        boolean remove = favs.contains(app.pkg);
        if (remove) favs.remove(app.pkg); else favs.add(app.pkg);
        prefs.edit().putString("favorites", TextUtils.join(",", favs)).apply();
        Toast.makeText(this, (remove ? "Removed from dock: " : "Added to dock: ") + app.label,
                Toast.LENGTH_SHORT).show();
        if (inDock) buildDock();
    }

    private Map<String, App> loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, App> map = new LinkedHashMap<>();
        List<ResolveInfo> all = new ArrayList<>();
        all.addAll(pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        all.addAll(pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        for (ResolveInfo ri : all) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || map.containsKey(pkg)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            map.put(pkg, new App(pkg, String.valueOf(ri.loadLabel(pm)), banner,
                    banner == null ? ri.loadIcon(pm) : null));
        }
        List<App> sorted = new ArrayList<>(map.values());
        Collections.sort(sorted, (a, b) -> a.label.compareToIgnoreCase(b.label));
        map.clear();
        for (App a : sorted) map.put(a.pkg, a);
        return map;
    }

    private void launch(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) {
            Toast.makeText(this, "Can't open " + pkg, Toast.LENGTH_SHORT).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Can't open " + pkg, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- wallpapers

    private void loadWallpaperList() {
        File dir = getExternalFilesDir("wallpapers"); // also creates the folder for adb push
        List<File> found = new ArrayList<>();
        File[] files = dir == null ? null : dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")) {
                    found.add(f);
                }
            }
        }
        Collections.shuffle(found);
        walls = found;
        wallIndex = -1;
    }

    private void nextWallpaper() {
        if (walls.isEmpty()) {
            wallIndex = (wallIndex + 1) % GRADIENTS.length;
            show(null, GRADIENTS[wallIndex]);
            return;
        }
        wallIndex = (wallIndex + 1) % walls.size();
        final File f = walls.get(wallIndex);
        final int w = getResources().getDisplayMetrics().widthPixels;
        io.execute(() -> {
            Bitmap b = decode(f, Math.max(w, 1280));
            handler.post(() -> show(b, GRADIENTS[0]));
        });
    }

    private static Bitmap decode(File f, int targetWidth) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= targetWidth) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private void show(Bitmap bitmap, int[] fallback) {
        final ImageView out = bg[front];
        final ImageView in = bg[1 - front];
        if (bitmap != null) {
            in.setImageBitmap(bitmap);
        } else {
            in.setImageDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR, fallback));
        }
        in.animate().alpha(1f).setDuration(1500).start();
        // Slow drift for a little life, like the Apple TV aerials.
        if (drift[1 - front] != null) drift[1 - front].cancel();
        drift[1 - front] = ObjectAnimator.ofPropertyValuesHolder(in,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f));
        drift[1 - front].setDuration(prefs.getInt("interval", 60) * 1000L + 1500);
        drift[1 - front].start();
        out.animate().alpha(0f).setDuration(1500).withEndAction(() -> out.setImageDrawable(null)).start();
        front = 1 - front;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}
