# Accueil Nocturne — launcher Android TV (Xiaomi MiTV-MOOQ0)

Implémentation de l'option **3a** du prototype Claude Design (`project/Interface TV.dc.html`), en Kotlin + Jetpack Compose.

- Les photos de fond défilent toutes les 7 s : fondu de 1,4 s et zoom lent sur 16 s.
- Un voile sombre recouvre le fond, avec par-dessus l'horloge (96), la date et le widget météo en verre (Open-Meteo, ville détectée par IP, Bruxelles par défaut).
- Dock en verre : favoris YouTube, RTBF Auvio, Crunchyroll et Prime Video, puis Apps / Sources / Réglages.
- **Focus** : la tuile grossit (×1,1) avec un anneau et un halo. Après 450 ms, l'animation de l'app remplace le fond en fondu de 1,4 s, en boucle, sans son et depuis le début.
- **OK** : la tuile s'agrandit jusqu'au plein écran en 650 ms (`cubic-bezier(.3,0,.1,1)`). L'accueil derrière se floute, s'assombrit et recule, puis l'app se lance. Au retour, la tuile s'efface.
- **Retour** : le focus quitte le dock et le diaporama reprend. Une flèche remet le focus sur le dock.
- **≡ Menu ou appui long sur OK** :
  - sur un favori : retirer, déplacer (◀ ▶ puis OK) ou infos ;
  - dans « Toutes les apps » : épingler, masquer ou infos.
- **Toutes les apps** : rail A–Z, recherche, favoris épinglés en tête et bannières des apps installées. Les apps masquées restent accessibles par la dernière tuile.

Toutes les mesures du prototype (canevas 960×540) sont reprises telles quelles. `DesignCanvas` adapte la densité pour qu'1 dp = 1 px de la maquette, quelle que soit la densité de la TV.

## 1. Compiler et installer

Ouvrez le dossier `launcher/` dans Android Studio (Ladybug ou plus récent), laissez Gradle se synchroniser, puis :

```sh
./gradlew assembleRelease          # ou assembleDebug
adb connect <IP_TV>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
```

> Ce projet a été écrit dans un environnement sans SDK Android : il n'a **pas encore été compilé**. Si Android Studio signale une erreur, recopiez-la à Claude Code.

## 2. Récupérer les fichiers d'origine de la TV

Le script `tools/extract_tv_assets.py` (Python 3.8 ou plus récent, et `adb`) se lance depuis un PC connecté au même réseau que la TV. Avant de le lancer :

1. Sur la TV, ouvrez Réglages › Préférences de l'appareil › À propos, et cliquez 7 fois sur « Build ».
2. Activez ensuite le débogage USB / réseau.

```sh
cd launcher/tools
python3 extract_tv_assets.py --ip <IP_TV> --push
```

| Sortie | Contenu |
| --- | --- |
| `out/anim/<clé>.mp4` | Vidéo que l'app publie pour l'écran d'accueil (`preview_video_uri` du TvProvider). À défaut, la plus grande vidéo trouvée dans son APK. |
| `out/anim/<clé>.jpg` | Image de repli (`poster_art_uri`) si l'app n'a pas de vidéo. |
| `out/photos/1..4.*` | Les plus grandes images de fond trouvées dans les APK du launcher d'origine et dans `/system/media`. |
| `out/candidates/photos/` | Toutes les images de fond trouvées, pour choisir à la main. |
| `out/splash/<clé>/` | Animations de démarrage des APK (Lottie `.json`, vidéos, `avd_*.xml`). Elles ne sont pas utilisées par le launcher. |

Les clés sont `youtube`, `auvio`, `crunchyroll` et `prime`. Pour un autre favori, la clé est le nom de son paquet.

L'option `--push` copie `photos/` et `anim/` dans `/sdcard/Android/data/be.magin.launcher/files/`. Le launcher relit ces fichiers à chaque retour à l'accueil, sans recompilation. Vous pouvez aussi copier ces dossiers dans `app/src/main/assets/` avant de compiler.

**Limites connues :**
- Selon le firmware, la TV peut refuser la lecture du TvProvider depuis ADB (« Permission Denial »). Le script passe alors aux APK.
- Sur Google TV, les photos du mode Ambiance viennent d'Internet et ne sont pas stockées sur la TV. Déposez vos propres photos dans `out/photos/` puis lancez `--push-only`.
- Dernier recours : `--record youtube` filme l'écran pendant que YouTube a le focus dans le launcher d'origine. Il faut ensuite recadrer la vidéo.

Si une app n'a ni vidéo ni image, le diaporama reste affiché quand elle a le focus.

## 3. En faire l'écran d'accueil

Google TV ne propose pas toujours de choisir le launcher. Via ADB :

```sh
# Android 10+ : définir l'activité d'accueil
adb shell cmd package set-home-activity be.magin.launcher/.MainActivity
```

Si le bouton Home ouvre encore l'ancien accueil, vous pouvez désactiver le launcher d'origine. C'est réversible : `pm enable` le rétablit.

```sh
adb shell pm list packages | grep -iE "launcher|tvhome|patchwall"
adb shell pm disable-user --user 0 <paquet_du_launcher_d_origine>
# retour en arrière : adb shell pm enable <paquet_du_launcher_d_origine>
```

Gardez ADB activé tant que vous testez : en cas de souci, `adb shell pm enable …` remet l'accueil d'origine.

## Réglages

- `Config.SOURCES_PACKAGE` : l'app qu'ouvre « Sources » (HDMI, antenne). `python3 tools/extract_tv_assets.py --list-sources` liste les candidates. Par défaut, le launcher essaie l'écran des entrées Android TV, puis Live TV, puis les Réglages.
- `Config.MAX_FAVORITES` (6) : nombre maximum de favoris dans le dock.
- `Brands.kt` : couleurs et noms des tuiles. Les tuiles reprennent les couleurs des services, mais les noms sont écrits en Inter : ce ne sont pas les logos officiels. Les noms de paquet d'Auvio sont des suppositions ; le launcher reconnaît aussi l'app à son nom (« auvio »).

## Différences connues avec la maquette

- **Flou de verre** (`backdrop-filter: blur(16px)`) : pas d'équivalent simple en Compose. Le dock et la météo utilisent la même surface à 70 % sans flou. Le fond derrière est de toute façon assombri par le voile.
- **Flou de l'accueil au lancement** : visible seulement sous Android 12 ou plus récent. Sur les versions antérieures, il ne reste que l'assombrissement et le recul.
- **Anneau de focus** : le README de la maquette indique `#cfd3e5`. J'ai suivi le prototype 3a, qui utilise accent-200 `#e7e5fe`.

## Structure

```
app/src/main/java/be/magin/launcher/
  MainActivity.kt        activité HOME (singleTask), ticks resume / Home
  AppLauncher.kt         lancement des apps, Sources, Réglages, Infos
  Config.kt
  model/Apps.kt          apps installées, marques, favoris
  model/Prefs.kt         DataStore : favoris (ordre) et apps masquées
  model/Weather.kt       Open-Meteo + icône selon le code WMO
  model/Media.kt         photos/ et anim/ (stockage externe de l'app, puis assets)
  ui/Theme.kt            tokens Nocturne, Inter, ombres, anneau de focus, clic D-pad
  ui/Background.kt       diaporama, animations des apps (2 lecteurs ExoPlayer en fondu enchaîné)
  ui/HomeScreen.kt       option 3a
  ui/AllAppsScreen.kt    option 1f
  ui/AppMenu.kt          menu ≡
tools/extract_tv_assets.py
```

Icônes : Phosphor 2.1.1 (MIT), converties en vector drawables. Police : Inter (OFL).
