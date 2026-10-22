#!/usr/bin/env python3
"""
Récupère sur la TV (ADB) les fichiers d'origine utilisés par le launcher Nocturne :

  out/anim/<clé>.mp4      animation de focus de chaque favori (Preview Programs du TvProvider)
  out/anim/<clé>.jpg      image de repli quand l'app ne publie pas de vidéo
  out/photos/1..4.*       fonds d'écran d'origine (APK du launcher Xiaomi / Google TV, /system/media…)
  out/splash/<clé>/       animations de démarrage trouvées dans chaque APK (Lottie .json, vidéos)
  out/candidates/photos/  toutes les images de fond trouvées, pour choisir à la main

Usage personnel, sur votre propre TV. Il faut Python 3.8+ et `adb` (Android platform-tools).

  python3 extract_tv_assets.py --ip 192.168.1.50           # tout extraire
  python3 extract_tv_assets.py --ip 192.168.1.50 --push    # … puis l'envoyer au launcher installé
  python3 extract_tv_assets.py --list-sources              # paquets candidats pour le bouton Sources
  python3 extract_tv_assets.py --record youtube            # dernier recours : filmer l'écran 10 s
"""
import argparse
import hashlib
import re
import shutil
import struct
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

LAUNCHER_PKG = "be.magin.launcher"
REMOTE_FILES = f"/sdcard/Android/data/{LAUNCHER_PKG}/files"

# Clé = nom de fichier attendu par le launcher (anim/<clé>.mp4). Même liste que Brands.kt.
FAVORITES = {
    "youtube": ["com.google.android.youtube.tv", "com.google.android.youtube.googletv"],
    "auvio": ["be.rtbf.auvio", "be.rtbf.auvio.tv", "be.rtbf.auvio.androidtv"],
    "crunchyroll": ["com.crunchyroll.crunchyroid"],
    "prime": ["com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"],
}
# Repli si aucun des paquets connus n'est installé : sous-chaîne du nom de paquet.
FAVORITE_HINTS = {"youtube": "youtube", "auvio": "auvio", "crunchyroll": "crunchyroll", "prime": "amazonvideo"}

WALLPAPER_PKG_RE = re.compile(r"launcher|patchwall|tvhome|ambient|screensaver|wallpaper|dream|backdrop|gallery", re.I)
SOURCES_PKG_RE = re.compile(r"input|source|tvplayer|livetv|hdmi|tvcenter|dtv|atv\.", re.I)
IMAGE_EXT = (".jpg", ".jpeg", ".png", ".webp")
VIDEO_EXT = (".mp4", ".webm", ".mkv")

ADB = ["adb"]


def log(msg):
    print(msg, flush=True)


def adb(*args, check=False, binary=False):
    result = subprocess.run(ADB + list(args), capture_output=True)
    if check and result.returncode != 0:
        sys.exit(f"adb {' '.join(args)} a échoué :\n{result.stderr.decode(errors='replace')}")
    return result.stdout if binary else result.stdout.decode(errors="replace")


def shell(cmd):
    return adb("shell", cmd)


# ---------------------------------------------------------------- connexion / paquets

def connect(ip, serial):
    global ADB
    if shutil.which("adb") is None:
        sys.exit("adb introuvable : installez Android platform-tools et ajoutez-les au PATH.")
    if ip:
        target = ip if ":" in ip else f"{ip}:5555"
        log(adb("connect", target).strip())
        serial = serial or target
    if serial:
        ADB = ["adb", "-s", serial]
    devices = [l for l in adb("devices").splitlines()[1:] if l.strip().endswith("device")]
    if not devices:
        sys.exit("Aucune TV connectée. Activez le débogage (réseau) sur la TV puis relancez avec --ip <IP>.\n"
                 "Si la TV affiche « Autoriser le débogage ? », acceptez puis relancez.")
    if len(devices) > 1 and not serial:
        sys.exit("Plusieurs appareils connectés : précisez --serial.")


def installed_packages():
    return {l.split(":", 1)[1].strip() for l in shell("pm list packages").splitlines() if l.startswith("package:")}


def resolve_favorites(packages):
    resolved = {}
    for key, candidates in FAVORITES.items():
        pkg = next((p for p in candidates if p in packages), None)
        if pkg is None:
            pkg = next((p for p in sorted(packages) if FAVORITE_HINTS[key] in p.lower()), None)
        if pkg:
            resolved[key] = pkg
            log(f"  {key:12} → {pkg}")
        else:
            log(f"  {key:12} → non installée")
    return resolved


def pull_apks(pkg, dest):
    """Retourne les APK (base + splits) du paquet, copiés dans dest/."""
    dest.mkdir(parents=True, exist_ok=True)
    paths = [l.split(":", 1)[1].strip() for l in shell(f"pm path {pkg}").splitlines() if l.startswith("package:")]
    apks = []
    for i, remote in enumerate(paths):
        local = dest / f"{pkg}-{i}.apk"
        if not local.exists():
            adb("pull", remote, str(local))
        if local.exists():
            apks.append(local)
    return apks


# ---------------------------------------------------------------- images

def image_size(data):
    """(largeur, hauteur) d'un PNG / JPEG / WebP, sans dépendance externe."""
    try:
        if data[:8] == b"\x89PNG\r\n\x1a\n":
            return struct.unpack(">II", data[16:24])
        if data[:2] == b"\xff\xd8":
            i = 2
            while i + 9 < len(data):
                if data[i] != 0xFF:
                    i += 1
                    continue
                marker = data[i + 1]
                if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                    h, w = struct.unpack(">HH", data[i + 5:i + 9])
                    return w, h
                i += 2 + struct.unpack(">H", data[i + 2:i + 4])[0]
        if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
            chunk = data[12:16]
            if chunk == b"VP8 ":
                w, h = struct.unpack("<HH", data[26:30])
                return w & 0x3FFF, h & 0x3FFF
            if chunk == b"VP8L":
                b = data[21:25]
                w = 1 + (((b[1] & 0x3F) << 8) | b[0])
                h = 1 + (((b[3] & 0xF) << 10) | (b[2] << 2) | ((b[1] & 0xC0) >> 6))
                return w, h
            if chunk == b"VP8X":
                w = 1 + int.from_bytes(data[24:27], "little")
                h = 1 + int.from_bytes(data[27:30], "little")
                return w, h
    except (struct.error, IndexError):
        pass
    return None


def is_wallpaper(size, minimum=(1280, 720)):
    return size is not None and size[0] >= minimum[0] and size[1] >= minimum[1] and size[0] > size[1]


def collect_apk_images(apk):
    found = []
    try:
        with zipfile.ZipFile(apk) as z:
            for name in z.namelist():
                low = name.lower()
                if not low.endswith(IMAGE_EXT) or low.endswith(".9.png"):
                    continue
                data = z.read(name)
                size = image_size(data)
                if is_wallpaper(size):
                    found.append((size, data, f"{apk.stem}:{name}"))
    except zipfile.BadZipFile:
        log(f"  ! {apk.name} illisible")
    return found


def extract_wallpapers(packages, work, out, count):
    log("\n[2/3] Fonds d'écran d'origine")
    images = []
    for pkg in sorted(p for p in packages if WALLPAPER_PKG_RE.search(p)):
        log(f"  APK {pkg}")
        for apk in pull_apks(pkg, work / "apks"):
            images += collect_apk_images(apk)

    listing = shell("find /system/media /product/media /vendor/media /oem/media -type f 2>/dev/null")
    for remote in listing.splitlines():
        remote = remote.strip()
        if not remote.lower().endswith(IMAGE_EXT):
            continue
        data = adb("exec-out", "cat", remote, binary=True)
        size = image_size(data)
        if is_wallpaper(size):
            images.append((size, data, remote))

    seen, unique = set(), []
    for size, data, origin in sorted(images, key=lambda x: -(x[0][0] * x[0][1])):
        digest = hashlib.sha1(data).hexdigest()
        if digest not in seen:
            seen.add(digest)
            unique.append((size, data, origin))

    candidates = out / "candidates" / "photos"
    candidates.mkdir(parents=True, exist_ok=True)
    for i, (size, data, origin) in enumerate(unique):
        ext = ".png" if data[:4] == b"\x89PNG" else ".webp" if data[:4] == b"RIFF" else ".jpg"
        (candidates / f"{i + 1:03d}_{size[0]}x{size[1]}{ext}").write_bytes(data)

    photos = out / "photos"
    photos.mkdir(parents=True, exist_ok=True)
    best = [u for u in unique if u[0][0] >= 1920 and u[0][1] >= 1080] or unique
    for i, (size, data, origin) in enumerate(best[:count]):
        ext = ".png" if data[:4] == b"\x89PNG" else ".webp" if data[:4] == b"RIFF" else ".jpg"
        (photos / f"{i + 1}{ext}").write_bytes(data)
        log(f"  photos/{i + 1}{ext}  {size[0]}×{size[1]}  ← {origin}")
    if not unique:
        log("  Aucune image ≥ 1280×720 trouvée. Sur Google TV, le mode Ambiance télécharge ses photos\n"
            "  depuis Internet : elles ne sont pas stockées sur la TV. Déposez vos propres photos dans out/photos/.")
    else:
        log(f"  {len(unique)} candidates dans {candidates} (renommez-en d'autres en photos/1.jpg… si besoin)")


# ---------------------------------------------------------------- animations de focus

PREVIEW_KEYS = ("package_name", "title", "preview_video_uri", "poster_art_uri", "type")


def parse_rows(output):
    rows = []
    split = re.compile(r", (?=(?:%s)=)" % "|".join(PREVIEW_KEYS))
    for line in output.splitlines():
        m = re.match(r"Row: \d+ (.*)", line.strip())
        if not m:
            continue
        row = {}
        for part in split.split(m.group(1)):
            k, _, v = part.partition("=")
            row[k] = None if v == "NULL" else v
        rows.append(row)
    return rows


def download(uri, dest):
    dest.parent.mkdir(parents=True, exist_ok=True)
    try:
        if uri.startswith(("http://", "https://")):
            req = urllib.request.Request(uri, headers={"User-Agent": "Mozilla/5.0 (Linux; Android 11; TV)"})
            with urllib.request.urlopen(req, timeout=60) as r:
                data = r.read()
        else:  # content:// ou file:// sur la TV
            data = adb("exec-out", "content", "read", "--uri", uri, binary=True)
        if len(data) < 1024:
            return False
        dest.write_bytes(data)
        return True
    except Exception as e:  # noqa: BLE001 — on continue avec les autres apps
        log(f"    ! {uri[:80]}… : {e}")
        return False


def extract_focus_animations(favorites, out):
    log("\n[1/3] Animations de focus (Preview Programs)")
    output = shell("content query --uri content://android.media.tv/preview_program "
                   "--projection package_name:title:preview_video_uri:poster_art_uri")
    if "Permission Denial" in output or "Error" in output[:200]:
        log("  ! La TV refuse la lecture du TvProvider depuis ADB sur ce firmware.\n"
            "    Repli : animations des APK (étape 3) ou --record <clé>.")
        return set()
    rows = parse_rows(output)
    log(f"  {len(rows)} programmes trouvés")
    done = set()
    anim = out / "anim"
    for key, pkg in favorites.items():
        mine = [r for r in rows if r.get("package_name") == pkg]
        videos = [r["preview_video_uri"] for r in mine if r.get("preview_video_uri")]
        posters = [r["poster_art_uri"] for r in mine if r.get("poster_art_uri")]
        if not mine:
            log(f"  {key:12} aucune preview publiée (ouvrez l'app une fois sur la TV, puis relancez)")
        for uri in videos:
            if ".m3u8" in uri or ".mpd" in uri:
                log(f"  {key:12} flux adaptatif ignoré : {uri[:70]}…")
                continue
            if download(uri, anim / f"{key}.mp4"):
                log(f"  {key:12} anim/{key}.mp4")
                done.add(key)
                break
        if key not in done:
            for uri in posters:
                if download(uri, anim / f"{key}.jpg"):
                    log(f"  {key:12} anim/{key}.jpg (image, pas de vidéo)")
                    break
    return done


def extract_splash(favorites, work, out, have_video):
    log("\n[3/3] Animations de démarrage dans les APK")
    for key, pkg in favorites.items():
        dest = out / "splash" / key
        found = []
        for apk in pull_apks(pkg, work / "apks"):
            try:
                with zipfile.ZipFile(apk) as z:
                    for name in z.namelist():
                        low = name.lower()
                        if low.startswith(("res/", "assets/")) and low.endswith(VIDEO_EXT):
                            found.append((name, z.read(name)))
                        elif low.endswith(".json") and (low.startswith("res/raw/") or "lottie" in low or "anim" in low):
                            data = z.read(name)
                            if b'"layers"' in data[:20000] and b'"fr"' in data[:20000]:
                                found.append((name, data))
                        elif low.startswith("res/") and "avd_" in low and low.endswith(".xml"):
                            found.append((name, z.read(name)))
            except zipfile.BadZipFile:
                continue
        if not found:
            log(f"  {key:12} rien trouvé")
            continue
        dest.mkdir(parents=True, exist_ok=True)
        for name, data in found:
            (dest / name.replace("/", "_")).write_bytes(data)
        videos = sorted((d for n, d in found if n.lower().endswith(VIDEO_EXT)), key=len, reverse=True)
        log(f"  {key:12} {len(found)} fichier(s) → splash/{key}/")
        if key not in have_video and videos:
            (out / "anim").mkdir(parents=True, exist_ok=True)
            (out / "anim" / f"{key}.mp4").write_bytes(videos[0])
            log(f"  {key:12} anim/{key}.mp4 ← plus grande vidéo de l'APK (à vérifier)")


# ---------------------------------------------------------------- autres commandes

def record(key, out, seconds):
    input(f"Sur la TV, ouvrez le launcher d'origine et placez le focus sur « {key} ».\n"
          f"Appuyez sur Entrée ici pour filmer {seconds} s…")
    shell(f"screenrecord --time-limit {seconds} /sdcard/nocturne_rec.mp4")
    dest = out / "anim" / f"{key}.mp4"
    dest.parent.mkdir(parents=True, exist_ok=True)
    adb("pull", "/sdcard/nocturne_rec.mp4", str(dest))
    shell("rm /sdcard/nocturne_rec.mp4")
    log(f"→ {dest} (l'écran entier est filmé : recadrez la vidéo si l'interface d'origine apparaît)")


def push(out):
    if LAUNCHER_PKG not in installed_packages():
        sys.exit(f"{LAUNCHER_PKG} n'est pas installé : installez d'abord l'APK du launcher.")
    for folder in ("photos", "anim"):
        local = out / folder
        files = sorted(p for p in local.glob("*") if p.is_file()) if local.exists() else []
        if not files:
            continue
        shell(f"mkdir -p {REMOTE_FILES}/{folder}")
        for f in files:
            adb("push", str(f), f"{REMOTE_FILES}/{folder}/{f.name}")
            log(f"  envoyé {folder}/{f.name}")
    log("Revenez à l'accueil : le launcher recharge les fichiers à chaque retour.")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ip", help="adresse IP de la TV (adb connect <ip>:5555)")
    ap.add_argument("--serial", help="numéro de série ADB si plusieurs appareils")
    ap.add_argument("--out", default=str(Path(__file__).resolve().parent / "out"), help="dossier de sortie")
    ap.add_argument("--photos", type=int, default=4, help="nombre de fonds à garder (défaut : 4)")
    ap.add_argument("--push", action="store_true", help="envoyer out/photos et out/anim au launcher installé")
    ap.add_argument("--push-only", action="store_true", help="envoyer sans rien extraire")
    ap.add_argument("--list-sources", action="store_true", help="lister les paquets candidats pour « Sources »")
    ap.add_argument("--record", metavar="CLÉ", help="filmer l'écran pendant que <CLÉ> a le focus (dernier recours)")
    ap.add_argument("--seconds", type=int, default=10)
    args = ap.parse_args()

    out = Path(args.out)
    work = out / ".work"
    connect(args.ip, args.serial)

    if args.list_sources:
        for p in sorted(installed_packages()):
            if SOURCES_PKG_RE.search(p):
                print(p)
        print("\nRenseignez le bon paquet dans app/src/main/java/be/magin/launcher/Config.kt (SOURCES_PACKAGE).")
        return
    if args.record:
        record(args.record, out, args.seconds)
        return
    if args.push_only:
        push(out)
        return

    packages = installed_packages()
    log("Favoris détectés :")
    favorites = resolve_favorites(packages)
    have_video = extract_focus_animations(favorites, out)
    extract_wallpapers(packages, work, out, args.photos)
    extract_splash(favorites, work, out, have_video)
    log(f"\nTerminé → {out}")
    if args.push:
        push(out)


if __name__ == "__main__":
    main()
