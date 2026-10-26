#!/usr/bin/env python3
"""Find, back up, debloat and re-home an Android TV over network ADB.

Every change is journalled next to the latest backup and mirrored into a
restore.sh, so everything this tool does can be undone. Packages are only
ever disabled for user 0 (pm disable-user), never uninstalled.

Transport: the `adb` binary if it works, otherwise the pure-Python
`adb-shell` package (pip install adb-shell) - handy when macOS blocks adb.
Force one with TV_BACKEND=adb or TV_BACKEND=python.
"""
import argparse
import concurrent.futures
import datetime
import ipaddress
import json
import os
import re
import shlex
import shutil
import socket
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
STATE = Path(os.environ.get("TV_STATE_DIR", HERE.parent / "state"))
CONFIG = STATE / "config.json"
SHOTS = STATE / "screenshots"
BACKUPS = STATE / "backups"
CATALOG = json.loads((HERE / "catalog.json").read_text())

LAUNCHER_PKG = "com.minimal.tvhome"
LAUNCHER_ACTIVITY = f"{LAUNCHER_PKG}/.MainActivity"
WALLPAPER_DIR = f"/sdcard/Android/data/{LAUNCHER_PKG}/files/wallpapers"
PKG_RE = re.compile(r"^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$")
# Settings the `tweaks` command may change; all are recorded for restore.
TWEAKS = [
    ("global", "window_animation_scale", "0.5", "Faster window animations"),
    ("global", "transition_animation_scale", "0.5", "Faster screen transitions"),
    ("global", "animator_duration_scale", "0.5", "Faster UI animations"),
]


def die(msg):
    print(f"error: {msg}", file=sys.stderr)
    sys.exit(1)


def ask(question, default=False):
    hint = "Y/n" if default else "y/N"
    while True:
        ans = input(f"{question} [{hint}] ").strip().lower()
        if not ans:
            return default
        if ans in ("y", "yes", "j", "ja"):
            return True
        if ans in ("n", "no", "nein"):
            return False


def load_config():
    return json.loads(CONFIG.read_text()) if CONFIG.exists() else {}


def save_config(cfg):
    STATE.mkdir(parents=True, exist_ok=True)
    CONFIG.write_text(json.dumps(cfg, indent=2))


# --------------------------------------------------------------------------
# Transport


class Device:
    def __init__(self, host, port=5555):
        self.host, self.port = host, port
        self.serial = f"{host}:{port}"
        want = os.environ.get("TV_BACKEND", "auto")
        self.backend = None
        if want in ("auto", "adb") and shutil.which("adb"):
            try:
                out = subprocess.run(["adb", "connect", self.serial], capture_output=True,
                                     text=True, timeout=20).stdout
                if "connected" in out and "cannot" not in out and "failed" not in out:
                    self.backend = "adb"
                    if "unauthorized" in self._adb_state():
                        print(">> Accept the 'Allow USB debugging?' prompt on the TV "
                              "(tick 'Always allow'), then press Enter.")
                        input()
                else:
                    print(f"adb connect said: {out.strip()}")
            except (OSError, subprocess.SubprocessError) as e:
                print(f"adb binary unusable ({e}); falling back to adb-shell")
        if self.backend is None:
            if want == "adb":
                die("adb backend requested but adb connect failed")
            self._connect_python()
            self.backend = "python"

    def _adb_state(self):
        r = subprocess.run(["adb", "-s", self.serial, "get-state"], capture_output=True, text=True)
        return r.stdout + r.stderr

    def _connect_python(self):
        try:
            from adb_shell.adb_device import AdbDeviceTcp
            from adb_shell.auth.keygen import keygen
            from adb_shell.auth.sign_pythonrsa import PythonRSASigner
        except ImportError:
            die("neither a working adb nor the adb-shell package is available: "
                "pip install adb-shell")
        key = Path.home() / ".android" / "adbkey"
        if not key.exists():
            key.parent.mkdir(parents=True, exist_ok=True)
            keygen(str(key))
        self._signer = PythonRSASigner(Path(str(key) + ".pub").read_text(), key.read_text())
        self._AdbDeviceTcp = AdbDeviceTcp
        self.dev = AdbDeviceTcp(self.host, self.port, default_transport_timeout_s=15)
        print(">> If the TV shows 'Allow USB debugging?', tick 'Always allow' and press OK.")
        self.dev.connect(rsa_keys=[self._signer], auth_timeout_s=90)

    def _reconnect_python(self):
        # Disabling launchers/recommendations or setting Home restarts SystemUI,
        # which briefly drops the ADB socket. Rebuild it instead of crashing.
        try:
            self.dev.close()
        except Exception:
            pass
        for attempt in range(6):
            time.sleep(2)
            try:
                self.dev = self._AdbDeviceTcp(self.host, self.port, default_transport_timeout_s=15)
                self.dev.connect(rsa_keys=[self._signer], auth_timeout_s=30)
                return
            except Exception:
                if attempt == 5:
                    raise

    def shell(self, cmd, check=False):
        if self.backend == "adb":
            r = subprocess.run(["adb", "-s", self.serial, "shell", cmd], capture_output=True,
                               text=True, timeout=120)
            out = r.stdout + r.stderr
        else:
            try:
                out = self.dev.shell(cmd, timeout_s=120, read_timeout_s=120) or ""
            except Exception:
                # One transparent reconnect-and-retry for a dropped socket.
                self._reconnect_python()
                out = self.dev.shell(cmd, timeout_s=120, read_timeout_s=120) or ""
        if check and ("Exception" in out or "Error" in out or "Failure" in out):
            raise RuntimeError(f"`{cmd}` failed: {out.strip()}")
        return out

    def pull(self, remote, local):
        if self.backend == "adb":
            subprocess.run(["adb", "-s", self.serial, "pull", remote, str(local)],
                           check=True, capture_output=True)
        else:
            self.dev.pull(remote, str(local))

    def push(self, local, remote):
        if self.backend == "adb":
            subprocess.run(["adb", "-s", self.serial, "push", str(local), remote],
                           check=True, capture_output=True)
        else:
            self.dev.push(str(local), remote)

    def install(self, apk):
        tmp = "/data/local/tmp/tvhome.apk"
        self.push(apk, tmp)
        out = self.shell(f"pm install -r -g {tmp}")
        if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in out:
            # Only ever done for our own launcher (signed with a different key).
            print("Existing Home build has a different signature; reinstalling it.")
            self.shell(f"pm uninstall {LAUNCHER_PKG}")
            out = self.shell(f"pm install -r -g {tmp}")
        self.shell(f"rm {tmp}")
        if "Success" not in out:
            raise RuntimeError(f"install failed: {out.strip()}")


def device(args=None):
    cfg = load_config()
    host = getattr(args, "host", None) or cfg.get("host")
    if not host:
        die("no TV configured yet: run `tv.py discover` then `tv.py connect <ip>`")
    return Device(host, int(cfg.get("port", 5555)))


# --------------------------------------------------------------------------
# Helpers on top of the transport


def screenshot(dev, label):
    SHOTS.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    name = re.sub(r"[^A-Za-z0-9_-]+", "-", label).strip("-") or "shot"
    local = SHOTS / f"{stamp}-{name}.png"
    dev.shell("screencap -p /data/local/tmp/tvshot.png")
    dev.pull("/data/local/tmp/tvshot.png", local)
    dev.shell("rm /data/local/tmp/tvshot.png")
    print(f"[screenshot] {local}")
    return local


def packages(dev, flag=""):
    out = dev.shell(f"pm list packages {flag} --user 0")
    return sorted({l.split(":", 1)[1].strip() for l in out.splitlines() if l.startswith("package:")})


def home_activity(dev):
    out = dev.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN "
                    "-c android.intent.category.HOME")
    lines = [l.strip() for l in out.splitlines() if "/" in l]
    return lines[-1] if lines else None


def foreground(dev):
    out = dev.shell("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'")
    m = re.search(r"([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)", out)
    return f"{m.group(1)}/{m.group(2)}" if m else None


def setting_get(dev, ns, key):
    v = dev.shell(f"settings get {ns} {key}").strip()
    return None if v in ("null", "") else v


def is_protected(pkg):
    return any(pkg == p.rstrip(".") or pkg.startswith(p if p.endswith(".") else p + ".")
               for p in CATALOG["protected_prefixes"])


def keyword_hit(pkg, words):
    parts = re.split(r"[._]", pkg.lower())
    return [w for w in words if w in parts or (len(w) > 3 and any(w in p for p in parts))]


def latest_backup():
    if not BACKUPS.exists():
        return None
    dirs = sorted(d for d in BACKUPS.iterdir() if (d / "snapshot.json").exists())
    return dirs[-1] if dirs else None


def require_backup():
    b = latest_backup()
    if not b:
        die("no backup yet - run `tv.py backup` before changing anything")
    return b


def journal(entry):
    b = require_backup()
    entry["time"] = datetime.datetime.now().isoformat(timespec="seconds")
    with open(b / "journal.jsonl", "a") as f:
        f.write(json.dumps(entry) + "\n")
    write_restore_script(b)


def read_journal(b):
    p = b / "journal.jsonl"
    return [json.loads(l) for l in p.read_text().splitlines() if l.strip()] if p.exists() else []


# --------------------------------------------------------------------------
# Restore script


def restore_steps(b):
    """Shell commands (run on the TV) that undo the journal, newest first."""
    snap = json.loads((b / "snapshot.json").read_text())
    steps, done = [], set()
    for e in reversed(read_journal(b)):
        if e["op"] == "disable" and e["pkg"] not in done:
            done.add(e["pkg"])
            steps.append((f"Re-enable {e['pkg']}", f"pm enable --user 0 {e['pkg']}"))
        elif e["op"] == "setting" and (e["ns"], e["key"]) not in done:
            done.add((e["ns"], e["key"]))
            cmd = (f"settings delete {e['ns']} {e['key']}" if e["old"] is None
                   else f"settings put {e['ns']} {e['key']} {shlex.quote(e['old'])}")
            steps.append((f"Restore setting {e['ns']}/{e['key']}", cmd))
    # The original home screen comes back once its package is enabled again.
    if snap.get("home") and any(e["op"] in ("home", "disable") for e in read_journal(b)):
        steps.append(("Make the original home screen the default again",
                      f"cmd package set-home-activity {snap['home']}"))
    return steps


def write_restore_script(b):
    snap = json.loads((b / "snapshot.json").read_text())
    lines = [
        "#!/usr/bin/env bash",
        f"# Restore script for {snap['model']} ({snap['host']}), backup {b.name}.",
        "# Undoes every change tv.py made since that backup. Generated automatically.",
        "# Usage: ./restore.sh [tv-ip]      (needs the adb binary;",
        "#        without adb run: python3 tvtool/tv.py restore)",
        "set -u",
        f'HOST="${{1:-{snap["host"]}}}"',
        'S="$HOST:5555"',
        'adb connect "$S" >/dev/null || { echo "cannot reach $S"; exit 1; }',
        'run() { echo "-> $1"; adb -s "$S" shell "$2"; }',
        "",
    ]
    for desc, cmd in restore_steps(b):
        lines.append(f"run {shlex.quote(desc)} {shlex.quote(cmd)}")
    lines += [
        "",
        "# Optional: remove the minimal Home app as well (it is ours, not the manufacturer's).",
        f'if [ "${{REMOVE_HOME:-0}}" = 1 ]; then run "Uninstall minimal Home" "pm uninstall {LAUNCHER_PKG}"; fi',
        'echo "Done. Press Home on the remote to check."',
    ]
    path = b / "restore.sh"
    path.write_text("\n".join(lines) + "\n")
    path.chmod(0o755)
    shutil.copy(path, STATE / "restore.sh")
    (STATE / "restore.sh").chmod(0o755)


# --------------------------------------------------------------------------
# Commands


def cmd_discover(args):
    if args.subnet:
        net = ipaddress.ip_network(args.subnet, strict=False)
    else:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("10.255.255.255", 1))
            ip = s.getsockname()[0]
        finally:
            s.close()
        net = ipaddress.ip_network(f"{ip}/24", strict=False)
    print(f"Scanning {net} for ADB (port 5555)...")

    def probe(addr):
        try:
            with socket.create_connection((str(addr), 5555), timeout=0.6):
                return str(addr)
        except OSError:
            return None

    with concurrent.futures.ThreadPoolExecutor(128) as ex:
        found = [a for a in ex.map(probe, net.hosts()) if a]
    if not found:
        print("No TV answered on port 5555. On the TV: Settings > System/Device Preferences > "
              "About > press 'Build' 7 times, then Developer options > enable 'USB debugging' "
              "/ 'Network debugging'. Then run discover again.")
        return
    for a in found:
        try:
            name = socket.gethostbyaddr(a)[0]
        except OSError:
            name = ""
        print(f"  {a}  {name}")
    print("Next: tv.py connect <ip>")


def cmd_connect(args):
    cfg = load_config()
    cfg.update(host=args.host, port=5555)
    save_config(cfg)
    dev = Device(args.host)
    props = {k: dev.shell(f"getprop {k}").strip() for k in (
        "ro.product.manufacturer", "ro.product.model", "ro.build.version.release",
        "ro.build.version.sdk", "ro.build.characteristics")}
    cfg["model"] = f"{props['ro.product.manufacturer']} {props['ro.product.model']}"
    save_config(cfg)
    print(f"Connected via {dev.backend}: {cfg['model']}, Android {props['ro.build.version.release']} "
          f"(SDK {props['ro.build.version.sdk']})")
    print(f"Current home screen: {home_activity(dev)}")
    screenshot(dev, "connected")


def cmd_screenshot(args):
    screenshot(device(), args.label)


def cmd_backup(args):
    dev = device()
    cfg = load_config()
    b = BACKUPS / datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    b.mkdir(parents=True)
    snap = {
        "host": cfg["host"], "model": cfg.get("model", "?"),
        "props": dev.shell("getprop"),
        "all": packages(dev, "-u"), "installed": packages(dev),
        "enabled": packages(dev, "-e"), "disabled": packages(dev, "-d"),
        "system": packages(dev, "-s"),
        "home": home_activity(dev),
        "settings": {ns: dev.shell(f"settings list {ns}") for ns in ("global", "secure", "system")},
    }
    (b / "snapshot.json").write_text(json.dumps(snap, indent=1))
    (b / "packages-with-paths.txt").write_text(dev.shell("pm list packages -f -u --user 0"))
    write_restore_script(b)
    screenshot(dev, "backup")
    print(f"Backup saved to {b} ({len(snap['installed'])} packages, home={snap['home']})")
    print(f"Restore script: {STATE / 'restore.sh'}")


def candidates(dev):
    installed, enabled, system = set(packages(dev)), set(packages(dev, "-e")), set(packages(dev, "-s"))
    cat = CATALOG["packages"]
    out = {"batch": [], "optional": [], "suspect": [], "launcher": [], "preinstalled_apps": []}
    for pkg in sorted(installed & enabled):
        if pkg in cat:
            c = cat[pkg]["category"]
            group = "batch" if c in ("ads", "telemetry", "junk") else c
            out[group].append({"pkg": pkg, "why": f"{c}: {cat[pkg]['desc']}"})
            continue
        if is_protected(pkg) or keyword_hit(pkg, CATALOG["protected_keywords"]):
            continue
        hits = keyword_hit(pkg, CATALOG["suspect_keywords"])
        vendor = any(pkg.startswith(p) for p in CATALOG["vendor_prefixes"])
        if pkg in system and (hits or vendor):
            why = ("name suggests " + "/".join(hits)) if hits else "manufacturer system app"
            out["suspect"].append({"pkg": pkg, "why": why})
        elif pkg in system and pkg in CATALOG["favorite_candidates"]:
            out["preinstalled_apps"].append({"pkg": pkg, "why": "preinstalled streaming app"})
    return out


def cmd_candidates(args):
    dev = device()
    c = candidates(dev)
    if args.json:
        print(json.dumps(c, indent=1))
        return
    for group, items in c.items():
        print(f"\n== {group} ({len(items)})")
        for i in items:
            print(f"  {i['pkg']:55s} {i['why']}")
    print("\n'batch' = known ads/telemetry/junk. Everything else: ask before disabling.")


def disable(dev, pkgs):
    done = []
    for pkg in pkgs:
        if not PKG_RE.match(pkg):
            print(f"  skip {pkg}: not a package name")
            continue
        if is_protected(pkg) or pkg == LAUNCHER_PKG:
            print(f"  skip {pkg}: protected system component")
            continue
        out = dev.shell(f"pm disable-user --user 0 {pkg}")
        if "disabled-user" in out or "new state: disabled" in out:
            journal({"op": "disable", "pkg": pkg})
            done.append(pkg)
            print(f"  disabled {pkg}")
        else:
            print(f"  FAILED {pkg}: {out.strip()}")
    # Verify against the package manager rather than trusting the output text.
    # Best-effort: a verify timeout must never discard an already-journalled change.
    try:
        still = [p for p in done if p not in set(packages(dev, "-d"))]
        if still:
            print(f"  WARNING: still enabled after disabling: {still}")
    except Exception as e:
        print(f"  (could not re-verify, changes are recorded anyway: {e})")
    return done


def cmd_disable(args):
    require_backup()
    dev = device()
    disable(dev, args.pkgs)
    screenshot(dev, "after-disable")


def cmd_enable(args):
    dev = device()
    for pkg in args.pkgs:
        if PKG_RE.match(pkg):
            print(f"  {pkg}: {dev.shell(f'pm enable --user 0 {pkg}').strip()}")
            journal({"op": "enable", "pkg": pkg})
    screenshot(dev, "after-enable")


def cmd_debloat(args):
    require_backup()
    dev = device()
    c = candidates(dev)
    screenshot(dev, "before-debloat")
    if c["batch"]:
        print("\nKnown ads / telemetry / junk:")
        for i in c["batch"]:
            print(f"  {i['pkg']:55s} {i['why']}")
        if ask(f"Disable these {len(c['batch'])}?", default=True):
            disable(dev, [i["pkg"] for i in c["batch"]])
            screenshot(dev, "after-batch")
    for group, label in (("optional", "Feature you might use"),
                         ("preinstalled_apps", "Preinstalled app"),
                         ("suspect", "Possible bloat (unverified)")):
        for i in c[group]:
            if ask(f"{label}: {i['pkg']} ({i['why']}). Disable?"):
                disable(dev, [i["pkg"]])
    screenshot(dev, "after-debloat")
    print(f"\nAll changes are reversible: {STATE / 'restore.sh'}")


def cmd_tweaks(args):
    require_backup()
    dev = device()
    for ns, key, val, desc in TWEAKS:
        old = setting_get(dev, ns, key)
        if old == val:
            continue
        if args.yes or ask(f"{desc} ({ns}/{key}: {old} -> {val})?", default=True):
            dev.shell(f"settings put {ns} {key} {val}")
            journal({"op": "setting", "ns": ns, "key": key, "old": old, "new": val})
            print(f"  {key} = {setting_get(dev, ns, key)}")


def cmd_install_launcher(args):
    require_backup()
    dev = device()
    apk = Path(args.apk)
    if not apk.exists():
        die(f"{apk} not found - build it (see README) or download it from the GitHub release")
    dev.install(apk)
    journal({"op": "install", "pkg": LAUNCHER_PKG})
    # Launch once so Android creates the app's wallpaper folder.
    extra = f" --es favorites {shlex.quote(args.favorites)}" if args.favorites else ""
    dev.shell(f"am start -n {LAUNCHER_ACTIVITY}{extra}")
    time.sleep(3)
    print(f"Installed. Foreground: {foreground(dev)}")
    screenshot(dev, "launcher-installed")


def cmd_favorites(args):
    dev = device()
    pkgs = [p.strip() for p in args.pkgs.split(",") if p.strip()]
    installed = set(packages(dev))
    for p in pkgs:
        if p not in installed:
            print(f"  warning: {p} is not installed")
    dev.shell(f"am start -n {LAUNCHER_ACTIVITY} --es favorites {shlex.quote(','.join(pkgs))}")
    time.sleep(2)
    screenshot(dev, "favorites")


def cmd_apps(args):
    dev = device()
    out = dev.shell("cmd package query-activities --brief -a android.intent.action.MAIN "
                    "-c android.intent.category.LEANBACK_LAUNCHER")
    for pkg in sorted({l.strip().split("/")[0] for l in out.splitlines() if "/" in l}):
        print(pkg)


def wikimedia_images(count):
    cats = ["Featured_pictures_of_landscapes", "Featured_pictures_of_mountains",
            "Featured_pictures_of_forests", "Featured_pictures_of_lakes"]
    urls = []
    for cat in cats:
        q = urllib.parse.urlencode({
            "action": "query", "generator": "categorymembers", "gcmtitle": f"Category:{cat}",
            "gcmtype": "file", "gcmlimit": "100", "prop": "imageinfo",
            "iiprop": "url|size", "iiurlwidth": "1920", "format": "json"})
        req = urllib.request.Request(f"https://commons.wikimedia.org/w/api.php?{q}",
                                     headers={"User-Agent": "minimal-tv-home/1.0 (personal use)"})
        try:
            pages = json.load(urllib.request.urlopen(req, timeout=30)).get("query", {}).get("pages", {})
        except OSError as e:
            print(f"  {cat}: {e}")
            continue
        for p in pages.values():
            ii = (p.get("imageinfo") or [{}])[0]
            # Landscape orientation only.
            if ii.get("thumburl") and ii.get("width", 0) > ii.get("height", 1) * 1.3:
                urls.append((p["title"], ii["thumburl"], ii.get("descriptionurl", "")))
    import random
    random.shuffle(urls)
    return urls[:count]


def cmd_wallpapers(args):
    dev = device()
    folder = Path(args.dir) if args.dir else STATE / "wallpapers"
    folder.mkdir(parents=True, exist_ok=True)
    if args.download:
        credits = []
        for i, (title, url, page) in enumerate(wikimedia_images(args.download)):
            dest = folder / f"nature-{i:02d}.jpg"
            req = urllib.request.Request(url, headers={"User-Agent": "minimal-tv-home/1.0"})
            try:
                dest.write_bytes(urllib.request.urlopen(req, timeout=60).read())
                credits.append(f"{dest.name}: {title} - {page}")
                print(f"  downloaded {title}")
            except OSError as e:
                print(f"  skip {title}: {e}")
        (folder / "CREDITS.txt").write_text("Wikimedia Commons, see each page for license\n"
                                            + "\n".join(credits) + "\n")
    imgs = [p for p in sorted(folder.iterdir()) if p.suffix.lower() in (".jpg", ".jpeg", ".png", ".webp")]
    if not imgs:
        die(f"no images in {folder} (use --download N or put JPEGs there)")
    dev.shell(f"mkdir -p {WALLPAPER_DIR}")
    for p in imgs:
        dev.push(p, f"{WALLPAPER_DIR}/{p.name}")
    print(f"Pushed {len(imgs)} wallpapers")
    dev.shell(f"am start -n {LAUNCHER_ACTIVITY} --ez reload true")
    time.sleep(4)
    screenshot(dev, "wallpapers")


def test_home(dev):
    """Open an app, press Home, and check our launcher comes to the front."""
    dev.shell("am start -a android.settings.SETTINGS")
    time.sleep(3)
    inside = foreground(dev)
    screenshot(dev, "home-test-inside-app")
    dev.shell("input keyevent KEYCODE_HOME")
    time.sleep(3)
    fg = foreground(dev)
    screenshot(dev, "home-test-after-home")
    ok = bool(fg) and fg.startswith(LAUNCHER_PKG)
    print(f"Inside app: {inside}\nAfter Home: {fg}\n=> {'PASS' if ok else 'FAIL'}")
    return ok


def cmd_set_home(args):
    b = require_backup()
    dev = device()
    if LAUNCHER_PKG not in packages(dev):
        die("install the launcher first: tv.py install-launcher <apk>")
    old = home_activity(dev)
    journal({"op": "home", "old": old})
    print(f"Current home: {old}")
    print(dev.shell(f"cmd package set-home-activity {LAUNCHER_ACTIVITY}").strip())
    if test_home(dev):
        return
    # Stock launchers that keep grabbing Home (Google TV launcherx, its setupwraith
    # shim, PatchWall...) have to be disabled - reversibly - for ours to win.
    stock = [p for p, v in CATALOG["packages"].items()
             if v["category"] == "launcher" and p in set(packages(dev, "-e"))]
    if old and old.split("/")[0] not in stock and old.split("/")[0] != LAUNCHER_PKG:
        stock.append(old.split("/")[0])
    print("Home still goes to the stock launcher. These need to be disabled:")
    for p in stock:
        print(f"  {p}  {CATALOG['packages'].get(p, {}).get('desc', '')}")
    if not ask("Disable them (restore.sh brings them back)?", default=True):
        return
    disable(dev, stock)
    dev.shell(f"cmd package set-home-activity {LAUNCHER_ACTIVITY}")
    if not test_home(dev):
        print(f"Home test still fails. Undo with {b / 'restore.sh'} or `tv.py restore`.")


def cmd_test_home(args):
    sys.exit(0 if test_home(device()) else 1)


def cmd_restore(args):
    b = Path(args.backup) if args.backup else require_backup()
    steps = restore_steps(b)
    if not steps:
        print("Nothing to restore.")
        return
    if args.dry_run:
        for desc, cmd in steps:
            print(f"-> {desc}: {cmd}")
        return
    dev = device()
    for desc, cmd in steps:
        print(f"-> {desc}: {dev.shell(cmd).strip()}")
    # Safety net: anything enabled at backup time that is disabled now.
    snap = json.loads((b / "snapshot.json").read_text())
    now_disabled = set(packages(dev, "-d"))
    for pkg in sorted(set(snap["enabled"]) & now_disabled):
        print(f"-> also re-enabling {pkg}: {dev.shell(f'pm enable --user 0 {pkg}').strip()}")
    if args.remove_home:
        print(dev.shell(f"pm uninstall {LAUNCHER_PKG}").strip())
    (b / "journal.jsonl").rename(b / f"journal.restored-{int(time.time())}.jsonl")
    write_restore_script(b)
    dev.shell("input keyevent KEYCODE_HOME")
    time.sleep(3)
    print(f"Home now: {foreground(dev)}")
    screenshot(dev, "after-restore")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("discover", help="scan the Wi-Fi for TVs with ADB enabled")
    s.add_argument("--subnet", help="e.g. 192.168.1.0/24 (default: this computer's /24)")
    s.set_defaults(f=cmd_discover)
    s = sub.add_parser("connect", help="connect and remember the TV")
    s.add_argument("host")
    s.set_defaults(f=cmd_connect)
    s = sub.add_parser("screenshot", help="save a screenshot from the TV")
    s.add_argument("label", nargs="?", default="manual")
    s.set_defaults(f=cmd_screenshot)
    sub.add_parser("backup", help="snapshot packages, settings and home screen").set_defaults(f=cmd_backup)
    s = sub.add_parser("candidates", help="list what could be disabled, grouped")
    s.add_argument("--json", action="store_true")
    s.set_defaults(f=cmd_candidates)
    sub.add_parser("debloat", help="interactive: disable ads/telemetry, ask about the rest").set_defaults(f=cmd_debloat)
    s = sub.add_parser("disable", help="disable specific packages (already agreed with the user)")
    s.add_argument("pkgs", nargs="+")
    s.set_defaults(f=cmd_disable)
    s = sub.add_parser("enable", help="re-enable specific packages")
    s.add_argument("pkgs", nargs="+")
    s.set_defaults(f=cmd_enable)
    s = sub.add_parser("tweaks", help="speed up animations (asks first)")
    s.add_argument("--yes", action="store_true")
    s.set_defaults(f=cmd_tweaks)
    sub.add_parser("apps", help="list launchable TV apps (for picking favourites)").set_defaults(f=cmd_apps)
    s = sub.add_parser("install-launcher", help="install the minimal Home app")
    s.add_argument("apk")
    s.add_argument("--favorites", help="comma-separated package names for the dock")
    s.set_defaults(f=cmd_install_launcher)
    s = sub.add_parser("favorites", help="set the dock apps, comma-separated, in order")
    s.add_argument("pkgs")
    s.set_defaults(f=cmd_favorites)
    s = sub.add_parser("wallpapers", help="push nature wallpapers to the Home app")
    s.add_argument("--dir", help="folder of JPEG/PNG images")
    s.add_argument("--download", type=int, metavar="N", help="fetch N landscape photos from Wikimedia Commons")
    s.set_defaults(f=cmd_wallpapers)
    sub.add_parser("set-home", help="make the minimal Home the default and test the Home button").set_defaults(f=cmd_set_home)
    sub.add_parser("test-home", help="open an app, press Home, check where it lands").set_defaults(f=cmd_test_home)
    s = sub.add_parser("restore", help="undo everything since the latest backup")
    s.add_argument("--backup", help="backup folder (default: latest)")
    s.add_argument("--dry-run", action="store_true")
    s.add_argument("--remove-home", action="store_true", help="also uninstall the minimal Home app")
    s.set_defaults(f=cmd_restore)
    args = p.parse_args()
    args.f(args)


if __name__ == "__main__":
    main()
