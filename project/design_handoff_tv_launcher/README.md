# Handoff: Launcher TV épuré — Xiaomi MiTV-MOOQ0

## Overview
Écran d'accueil minimal pour une TV Xiaomi sous Android TV / Google TV. Il propose 4 favoris (YouTube, RTBF Auvio, Crunchyroll, Prime Video), un accès à toutes les apps, aux sources et aux réglages, et un widget météo. Le fond passe en diaporama sur les photos d'origine de la TV. Quand une app a le focus, **sa propre animation d'origine** remplace le fond. Au lancement, la tuile s'agrandit jusqu'au plein écran.

Prototype de référence : `Interface TV.dc.html`, option **3a** (en haut de la page). Les options 1x et 2x sont des explorations antérieures.

## About the Design Files
Les fichiers HTML sont des **références de design** : ils montrent le rendu et le comportement attendus, ce n'est pas du code de production. Il faut les recréer dans un vrai launcher Android TV. Recommandation : Kotlin + Jetpack Compose for TV (`androidx.tv:tv-material`), avec une app déclarée comme launcher (`CATEGORY_HOME` + `CATEGORY_LEANBACK_LAUNCHER`).

## Fidelity
Haute fidélité pour 3a : couleurs, typo, espacements et timings sont définitifs. Basse fidélité pour 1a–1f.

---

## TÂCHE 1 — Extraire les assets d'origine de la TV (prioritaire)
L'utilisateur n'a ni les vidéos ni les photos d'origine. Il faut les récupérer sur la TV via ADB, pour un usage personnel sur son propre appareil.

Préparation : sur la TV, Réglages › Préférences de l'appareil › À propos, puis cliquer 7× sur « Build » pour activer les options développeur. Activer ensuite « Débogage USB / réseau ». Puis `adb connect <IP_TV>:5555`.

### 1a. Animations de focus (les « previews » des apps)
Sur Google TV / Android TV, la vidéo qui joue derrière une app ayant le focus provient des **Preview Programs** que chaque app publie dans le TvProvider (`TvContractCompat.PreviewPrograms`, champ `preview_video_uri`).
```sh
adb shell content query --uri content://android.media.tv/preview_program \
  --projection package_name:title:preview_video_uri:poster_art_uri
adb shell content query --uri content://android.media.tv/channel --projection package_name:display_name
```
- Télécharger les `preview_video_uri` (ou `poster_art_uri` en repli) pour `com.google.android.youtube.tv`, `be.rtbf.auvio` (vérifier le nom exact du paquet avec `pm list packages`), `com.crunchyroll.crunchyroid`, `com.amazon.amazonvideo.livingroom`.
- Si une app ne publie pas de preview, chercher l'animation dans son APK (voir 1c).
- Sortie attendue : `anim/youtube.mp4`, `anim/auvio.mp4`, `anim/crunchyroll.mp4`, `anim/prime.mp4`, à côté du fichier HTML. Le prototype les charge automatiquement.

### 1b. Fonds d'écran d'origine
```sh
adb shell pm list packages | grep -iE "launcher|patchwall|tvlauncher|ambient|screensaver|wallpaper"
adb shell pm path <paquet>
adb pull <chemin.apk> .
unzip -o <apk> -d out/ && find out/res out/assets -iE -name "*.jpg" -o -name "*.webp" -o -name "*.png" | xargs ls -S | head -50
```
Garder les plus grandes images (≥ 1920×1080). Vérifier aussi `/system/media/` et `/product/media/`. Sortie : `photos/1.jpg … 4.jpg`.

### 1c. Animations de lancement / splash des apps
Pour chaque APK (`pm path` puis `pull` et `unzip`) :
- `res/raw/*.json` → animations Lottie (lire avec `lottie-android`) ;
- `res/drawable*/avd_*.xml` / `windowSplashScreenAnimatedIcon` dans le thème → splash Android 12+ ;
- `res/raw/*.mp4|*.webm` → vidéos d'intro.

Utilisez `apktool d` pour décoder les XML.

---

## TÂCHE 2 — Implémenter le launcher

### Écran (1920×1080 ; le prototype fait 960×540, multiplier par 2)
- **Fond** : 4 photos empilées en fondu de 1,4 s ease, chacune avec un zoom lent (scale 1→1,1, translate −1,5 %/−1 %, 16 s, aller-retour). Changement de photo toutes les 7 s, uniquement si aucune app n'a le focus.
- **Voile** : dégradé horizontal `#161826` à 85 % → transparent à 55 %, et dégradé vertical `#161826` à 90 % en bas → transparent à 45 %.
- **Horloge** : 192 px, poids 300, interlettrage −0,04 em. Date en 36 px, `#cfd3e5`. Position x = 112, y = 220.
- **Widget météo** : carte en verre (surface `#232532` à 70 %, flou 16 px, bordure `#3f424d`, rayon 14 px). Icône + 14° + ville, séparateur, puis prévisions sur 3 jours. Données suggérées : Open-Meteo, sans clé, géolocalisée par IP.
- **Points du diaporama** (en haut à droite) : 4 points de 4 px de haut, l'actif fait 20 px de large en `#e9e9ed`, les autres 6 px en `#75798c`.
- **Dock** (en bas à gauche, 112/80 px) : carte en verre, padding 24 px. 4 tuiles 256×144, rayon 8, gap 24 px, aux couleurs des marques (YouTube `#FF0033`, Auvio dégradé `#0c1033→#23145a`, Crunchyroll `#F47521`, Prime `#0F171E` avec texte `#1A98FF`). Ensuite un séparateur, puis Apps / Sources / Réglages (icônes Phosphor 48 px).
- **Focus** : scale 1,1, anneau 4 px `#232532` + 8 px `#cfd3e5`, halo `#9184d9` à 40 %, transition 180 ms.

### Comportement
- D-pad ◀ ▶ déplace le focus. **450 ms** après la prise de focus, l'animation de l'app (1a) remplace le fond en fondu de 1,4 s, lue en boucle et sans son, depuis le début. Retour ou perte du focus : le diaporama reprend.
- OK lance l'app : la tuile passe de son rectangle dans le dock au plein écran (left/top/width/height, rayon 8 → 0, 650 ms, `cubic-bezier(.3,0,.1,1)`). Son logo grossit ×3. L'accueil derrière passe en blur 10 px, luminosité 0,6 et scale 0,96. Ensuite `startActivity(getLeanbackLaunchIntentForPackage(pkg))`. Sur Android, utiliser `ActivityOptions.makeScaleUpAnimation` à partir du rectangle de la tuile.
- Apps : grille A–Z de toutes les apps installées (`queryIntentActivities` avec `CATEGORY_LEANBACK_LAUNCHER`). Le menu sur une app propose épingler / déplacer / masquer (voir option 1f).
- Les favoris sont persistés dans DataStore et réordonnables par appui long.

### State
`wpIndex`, `focusedApp?`, `backgroundApp?` (focus après 450 ms), `launchingApp?`, `favorites[]`, `weather`.

## Design Tokens (Nocturne)
Fond `#161826` · Surface `#232532` · Texte `#e9e9ed` · Accent `#9184d9` / accent-300 `#c2b9f0` (approx.) · Neutres 200 `#e4e7f5`, 300 `#cfd3e5`, 400 `#b2b6ca`, 500 `#9397ab`, 600 `#75798c`, 700 `#595d6c`, 800 `#3f424d`, 900 `#292b31` · Police Inter, titres en poids 500 maximum · Rayons 4 / 8 / 14 · Icônes Phosphor. Les valeurs complètes sont dans `_ds/.../styles.css`.

## Files
- `Interface TV.dc.html` — prototype (option 3a = référence finale)
- `support.js`, `image-slot.js`, `_ds/` — nécessaires pour ouvrir le prototype dans un navigateur
- `anim/` et `photos/` — à créer avec les résultats de la Tâche 1
