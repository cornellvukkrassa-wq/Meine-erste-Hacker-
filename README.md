# Meine-erste-Hacker-

## Android TV debloat + minimal home screen

Makes a slow, ad-filled Android TV / Google TV fast again. It works over Wi-Fi ADB from your own computer:

- **Backs up first.** It snapshots every package state, all settings and the current home screen before it changes anything.
- **Reversible only.** It disables manufacturer ads, telemetry and junk for user 0 (`pm disable-user`). It never uninstalls them.
- **Asks before touching anything you might use.** That covers Chromecast, Assistant, YouTube, preinstalled apps and unknown vendor apps.
- **Installs a minimal home screen** in the Apple TV style: a dock of favourite apps, a clock and rotating nature wallpapers.
- **Screenshots every step** from the TV into `state/screenshots/`.
- **Writes a restore script** (`state/restore.sh`) and updates it after every change.

### 0. On the TV

Open Settings › System (or Device Preferences) › About and press **Build** 7 times. Then go to Developer options and turn on **USB debugging**. Turn on **Network debugging** too if your TV has it. When your computer first connects, a prompt appears on the TV: tick **Always allow**.

### 1. On your computer (same Wi-Fi)

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install -r tvtool/requirements.txt   # adb-shell: used when adb is missing or macOS blocks it

python3 tvtool/tv.py discover            # finds TVs with ADB on your /24
python3 tvtool/tv.py connect 192.168.1.50
python3 tvtool/tv.py backup              # required before any change
```

### 2. Debloat

```bash
python3 tvtool/tv.py candidates          # read-only: what could go, grouped
python3 tvtool/tv.py debloat             # interactive: batch for known ads/telemetry, asks about the rest
python3 tvtool/tv.py tweaks              # optional: 0.5x animations (asks)
```

When Claude drives this for you, it runs `candidates`, asks you in chat, and then runs `tv.py disable <pkg>...` with only the packages you approved.

### 3. Minimal home screen

Download `tvhome.apk` from the **launcher-latest** release, which GitHub Actions builds. To build it yourself instead, run `gradle -p launcher assembleRelease`; this needs the Android SDK.

```bash
python3 tvtool/tv.py apps                                    # installed TV apps
python3 tvtool/tv.py install-launcher tvhome.apk \
    --favorites com.netflix.ninja,com.google.android.youtube.tv
python3 tvtool/tv.py wallpapers --download 20                # landscape photos from Wikimedia Commons
python3 tvtool/tv.py set-home                                # makes it default + tests Home from inside an app
```

On Google TV, `set-home` also offers to disable `com.google.android.apps.tv.launcherx` and `com.google.android.tungsten.setupwraith`. If setupwraith stays enabled, the Home button bypasses any custom launcher. `test-home` opens Settings, presses Home, and checks that the minimal home screen is in front. It screenshots both steps.

On the TV, long-press OK on a tile to add it to the dock or remove it. Press Menu or select **All apps** to see every app.

### Undo

```bash
./state/restore.sh 192.168.1.50          # uses adb
python3 tvtool/tv.py restore             # same, without adb (adb-shell); --remove-home also removes the launcher
```

The restore script re-enables everything the tool disabled and restores the changed settings. It also makes the original home screen the default again.
