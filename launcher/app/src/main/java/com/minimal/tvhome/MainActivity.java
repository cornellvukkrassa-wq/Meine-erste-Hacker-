package com.minimal.tvhome;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private ImageView[] bg = new ImageView[2];
    private int front = 0;
    private final ObjectAnimator[] drift = new ObjectAnimator[2];
    private final java.util.List<android.animation.Animator> bgAnims = new ArrayList<>();
    private LinearLayout dock;
    private TextView greeting;
    private TextView heroTitle;
    private ValueAnimator heroRgb;
    private LinearLayout eyes;
    private View eyeL, eyeR;
    private final java.util.Random rnd = new java.util.Random();
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
        if (heroRgb != null) {
            if (heroRgb.isPaused()) heroRgb.resume(); else if (!heroRgb.isStarted()) heroRgb.start();
        }
        buildDock();
        handler.removeCallbacks(rotate);
        handler.post(rotate);
        handler.removeCallbacks(eyesRunnable);
        handler.postDelayed(eyesRunnable, 3000);   // first sighting a few seconds in
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotate);
        handler.removeCallbacks(eyesRunnable);
        if (eyes != null) eyes.setAlpha(0f);
        if (heroRgb != null && heroRgb.isRunning()) heroRgb.pause();
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
        // Deep, slightly diagonal dark base - never a flat single colour.
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0A0E1A, 0xFF131A2E, 0xFF0B0F1C}));

        // Soft, slowly drifting colour clouds that suit the dark palette.
        addAurora(root);

        for (int k = 0; k < 2; k++) {
            bg[k] = new ImageView(this);
            bg[k].setScaleType(ImageView.ScaleType.CENTER_CROP);
            bg[k].setAlpha(0f);   // stays empty unless the user adds photos
            root.addView(bg[k], match());
        }

        // A gentle vignette so text and dock stay readable over the colour clouds.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66000000, 0x1A000000, 0x33000000, 0xAA000000}));
        root.addView(scrim, match());

        // Easter egg: a pair of glowing eyes that now and then peer out of the
        // dark, blink once and vanish - our own spooky touch, no FNAF assets.
        eyeL = glowEye();
        eyeR = glowEye();
        eyes = new LinearLayout(this);
        eyes.setOrientation(LinearLayout.HORIZONTAL);
        eyes.setAlpha(0f);
        eyes.addView(eyeL, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout.LayoutParams erp = new LinearLayout.LayoutParams(dp(24), dp(24));
        erp.leftMargin = dp(28);
        eyes.addView(eyeR, erp);
        root.addView(eyes, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

        // Top-right: time, with the weekday and date quietly underneath.
        LinearLayout clockCol = new LinearLayout(this);
        clockCol.setOrientation(LinearLayout.VERTICAL);
        clockCol.setGravity(Gravity.END);
        // Time as HH:mm with a smaller live seconds counter next to it.
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.BOTTOM | Gravity.END);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(34);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setShadowLayer(12, 0, 2, 0x88000000);
        clock.setGravity(Gravity.END);
        timeRow.addView(clock);
        TextClock secs = new TextClock(this);
        secs.setFormat12Hour(":ss");
        secs.setFormat24Hour(":ss");
        secs.setTextColor(0xB3FFFFFF);
        secs.setTextSize(18);
        secs.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        secs.setShadowLayer(8, 0, 1, 0x88000000);
        LinearLayout.LayoutParams secLp = new LinearLayout.LayoutParams(-2, -2);
        secLp.leftMargin = dp(3);
        secLp.bottomMargin = dp(5);
        timeRow.addView(secs, secLp);
        clockCol.addView(timeRow);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEE, d. MMM");
        date.setFormat24Hour("EEE, d. MMM");
        date.setTextColor(0xCCFFFFFF);
        date.setTextSize(15);
        date.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        date.setShadowLayer(8, 0, 1, 0x88000000);
        date.setGravity(Gravity.END);
        clockCol.addView(date);

        // A settings button sits next to the clock, reachable with the D-pad.
        FrameLayout settingsBtn = new FrameLayout(this);
        settingsBtn.setFocusable(true);
        GradientDrawable gearBg = new GradientDrawable();
        gearBg.setShape(GradientDrawable.OVAL);
        gearBg.setColor(0x33FFFFFF);
        settingsBtn.setBackground(gearBg);
        ImageView gear = new ImageView(this);
        gear.setImageDrawable(getDrawable(android.R.drawable.ic_menu_manage));
        gear.setColorFilter(Color.WHITE);
        FrameLayout.LayoutParams gip = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER);
        settingsBtn.addView(gear, gip);
        settingsBtn.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.2f : 1f).scaleY(has ? 1.2f : 1f).setDuration(150).start();
            ((GradientDrawable) v.getBackground()).setColor(has ? 0x66FFFFFF : 0x33FFFFFF);
            if (has) setHero("Einstellungen");
        });
        settingsBtn.setOnClickListener(v -> openSettings());

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams gbp = new LinearLayout.LayoutParams(dp(52), dp(52));
        gbp.setMargins(0, 0, dp(22), 0);
        topRow.addView(settingsBtn, gbp);
        topRow.addView(clockCol);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        clp.setMargins(0, dp(28), dp(48), 0);
        root.addView(topRow, clp);

        // Center stage: the name of the currently focused app, large and light,
        // updating as you move along the dock - fills the screen, Apple-TV style.
        heroTitle = new TextView(this);
        heroTitle.setTextColor(Color.WHITE);
        heroTitle.setTextSize(58);
        heroTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heroTitle.setLetterSpacing(0.04f);
        heroTitle.setAllCaps(true);
        heroTitle.setShadowLayer(26, 0, 4, 0xB3000000);   // soft cinematic glow
        heroTitle.setSingleLine(true);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.setMargins(dp(48), 0, dp(48), dp(220));
        root.addView(heroTitle, hlp);

        // Permanent RGB: the title's colours cycle through the whole spectrum.
        heroRgb = ValueAnimator.ofFloat(0f, 1f);
        heroRgb.setDuration(5000);
        heroRgb.setRepeatCount(ValueAnimator.INFINITE);
        heroRgb.setInterpolator(new android.view.animation.LinearInterpolator());
        heroRgb.addUpdateListener(a -> paintHeroRgb((float) a.getAnimatedValue()));
        heroRgb.start();

        // Top-left: a greeting that follows the time of day - a bit of warmth.
        greeting = new TextView(this);
        greeting.setTextColor(Color.WHITE);
        greeting.setTextSize(32);
        greeting.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        greeting.setLetterSpacing(0.02f);
        greeting.setShadowLayer(12, 0, 2, 0x88000000);
        greeting.setText(greetingText());
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        glp.setMargins(dp(48), dp(30), 0, 0);
        root.addView(greeting, glp);

        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(10), dp(10), dp(10), dp(10));
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        // A lighter, frosted bar so the dock floats rather than sits in a heavy box.
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0x1AFFFFFF);
        glass.setCornerRadius(dp(24));
        glass.setStroke(dp(1), 0x2EFFFFFF);
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

    // A handful of big, soft, coloured glows drifting on the dark base - subtle
    // motion that reads as "alive" without photos and never fights the content.
    private void addAurora(FrameLayout root) {
        int[][] blobs = {
                // color(ARGB)  sizeDp  gravity                      dxDp dyDp durMs
                {0x404C5BD4, 720, Gravity.TOP | Gravity.START,       160,  90, 23000},
                {0x3826A69A, 640, Gravity.BOTTOM | Gravity.END,     -150, -70, 29000},
                {0x307E57C2, 560, Gravity.TOP | Gravity.END,        -120, 120, 34000},
                {0x2A2E77C7, 600, Gravity.BOTTOM | Gravity.START,    130, -90, 26000},
        };
        for (int[] b : blobs) {
            int size = dp(b[1]);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            g.setGradientRadius(size / 2f);
            g.setColors(new int[]{b[0], 0x00000000});
            View v = new View(this);
            v.setBackground(g);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, b[2]);
            lp.setMargins(-size / 4, -size / 4, -size / 4, -size / 4);
            root.addView(v, lp);
            ObjectAnimator move = ObjectAnimator.ofPropertyValuesHolder(v,
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0, dp(b[3])),
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0, dp(b[4])),
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f),
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0.65f, 1f));
            move.setDuration(b[5]);
            move.setRepeatCount(ObjectAnimator.INFINITE);
            move.setRepeatMode(ObjectAnimator.REVERSE);
            move.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            move.start();
            bgAnims.add(move);
        }
    }

    private View glowEye() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(dp(13));
        g.setColors(new int[]{0xFFFFC24D, 0x44FF8A00, 0x00000000});   // amber glow -> dark (as before)
        View v = new View(this);
        v.setBackground(g);
        return v;
    }

    // Appear at a random dark spot, hold, blink once, fade out, then schedule
    // the next sighting a good while later. Posted/removed with resume/pause.
    private final Runnable eyesRunnable = new Runnable() {
        @Override public void run() {
            if (eyes == null) return;
            View root = eyes.getRootView();
            int rw = root.getWidth(), rh = root.getHeight();
            if (rw > dp(260) && rh > dp(400)) {
                eyes.setTranslationX(dp(80) + rnd.nextInt(rw - dp(240)));
                eyes.setTranslationY(dp(140) + rnd.nextInt(rh - dp(320)));
            }
            eyeL.setScaleY(1f);
            eyeR.setScaleY(1f);
            eyes.animate().alpha(1f).setDuration(900).start();
            // Randomly: both blink, only one, or neither - so it feels alive.
            int who = rnd.nextInt(4);
            if (who == 0 || who == 1) handler.postDelayed(() -> blink(eyeL), 1500);
            if (who == 0 || who == 2) handler.postDelayed(() -> blink(eyeR), 1560);
            handler.postDelayed(() -> eyes.animate().alpha(0f).setDuration(1100).start(), 2300);
            handler.postDelayed(this, 3000 + rnd.nextInt(3000));   // every ~3-6s
        }
    };

    private void blink(View eye) {
        eye.animate().scaleY(0.1f).setDuration(110)
                .withEndAction(() -> eye.animate().scaleY(1f).setDuration(130).start()).start();
    }

    // Width of the current hero text, so the RGB gradient spans the whole word.
    private float heroTextWidth = 1f;

    private void measureHero() {
        String s = heroTitle.getText().toString();
        if (s.isEmpty()) s = " ";
        heroTextWidth = Math.max(heroTitle.getPaint().measureText(s),
                heroTitle.getPaint().measureText(s.toUpperCase(Locale.getDefault())));
    }

    // Continuously cycling rainbow across the title - a permanent RGB glow.
    private void paintHeroRgb(float phase) {
        if (heroTitle == null || heroTitle.getText().length() == 0) return;
        int n = 6;
        int[] cols = new int[n + 1];
        for (int i = 0; i <= n; i++) {
            float hue = (phase * 360f + i * (360f / n)) % 360f;
            cols[i] = Color.HSVToColor(new float[]{hue, 0.7f, 1f});
        }
        heroTitle.getPaint().setShader(
                new LinearGradient(0, 0, heroTextWidth, 0, cols, null, Shader.TileMode.CLAMP));
        heroTitle.invalidate();
    }

    private void setHero(String text) {
        if (heroTitle == null) return;
        if (text != null && text.equals(heroTitle.getText().toString())) return;
        heroTitle.setText(text == null ? "" : text);
        measureHero();
        heroTitle.setAlpha(0f);
        heroTitle.setTranslationY(dp(8));
        heroTitle.animate().alpha(1f).translationY(0).setDuration(180).start();
    }

    private void openSettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Einstellungen nicht verfügbar", Toast.LENGTH_SHORT).show();
        }
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
        // A soft translucent card with a hairline edge - clean, not a heavy block.
        final GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(16));
        shape.setColor(0xD91B1D26);
        shape.setStroke(dp(1), 0x22FFFFFF);
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
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(32), dp(32));
                ip.bottomMargin = dp(6);
                col.addView(iv, ip);
            }
            TextView tv = new TextView(this);
            tv.setText(app.label);
            tv.setTextColor(0xF2FFFFFF);
            tv.setTextSize(12);
            tv.setLetterSpacing(0.02f);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(10), 0, dp(10), 0);
            col.addView(tv);
            t.addView(col, match());
        }
        t.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.08f : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(12) : 0).setDuration(160).start();
            // Clean highlight: a bright hairline ring instead of a big jump in size.
            shape.setStroke(dp(has ? 3 : 1), has ? 0xF2FFFFFF : 0x22FFFFFF);
            // Only the dock drives the big center title; the drawer has its own grid.
            if (has && inDock) setHero(app.pkg.isEmpty() ? "Alle Apps" : app.label);
        });
        t.setOnClickListener(v -> launch(app.pkg));
        t.setOnLongClickListener(v -> {
            toggleFavorite(app, inDock);
            return true;
        });
        return t;
    }

    private LinearLayout.LayoutParams dockTileParams() {
        // Smaller tiles with air between them - compact and clean, not crammed.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(150), dp(86));
        lp.setMargins(dp(11), 0, dp(11), 0);
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
            lp.width = dp(150);
            lp.height = dp(86);
            lp.setMargins(dp(14), dp(14), dp(14), dp(14));
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
            return;   // no photos: the animated aurora background carries the screen
        }
        wallIndex = (wallIndex + 1) % walls.size();
        final File f = walls.get(wallIndex);
        final int w = getResources().getDisplayMetrics().widthPixels;
        io.execute(() -> {
            Bitmap b = decode(f, Math.max(w, 1280));
            if (b != null) handler.post(() -> show(b));
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

    private void show(Bitmap bitmap) {
        final ImageView out = bg[front];
        final ImageView in = bg[1 - front];
        in.setImageBitmap(bitmap);
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
        for (android.animation.Animator a : bgAnims) a.cancel();
    }
}
