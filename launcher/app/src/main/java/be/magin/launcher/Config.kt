package be.magin.launcher

object Config {
    /**
     * Package of the TV's "Sources" app (HDMI, antenna). null = try the standard Android TV inputs
     * screen, then Live TV, then Settings. `python3 tools/extract_tv_assets.py --list-sources` lists candidates.
     */
    val SOURCES_PACKAGE: String? = null

    /** The dock fits 6 tiles next to Apps / Sources / Réglages at 1920×1080. */
    const val MAX_FAVORITES = 6
}
