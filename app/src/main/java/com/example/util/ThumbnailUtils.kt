package com.example.util

/**
 * YouTube Music's InnerTube API returns thumbnail URLs sized for the context
 * they were requested in - usually a small ~60-120px square meant for a
 * search-result row. Using that same URL for a big surface (the full-width
 * Now Playing artwork, for example) means Coil has to upscale a tiny bitmap,
 * which is exactly what shows up as a blurry thumbnail.
 *
 * Google's image CDN (lh3.googleusercontent.com / yt3.ggpht.com, which is
 * what these thumbnail URLs point at) supports requesting a different
 * resolution of the *same* image by swapping the size directive at the end
 * of the URL - no extra network round trip, no separate endpoint. This just
 * rewrites that directive before the URL ever reaches Coil, so every screen
 * asks for artwork at (roughly) the resolution it's actually going to draw.
 *
 * Falls back to the original URL untouched for anything that isn't a
 * Google CDN thumbnail (e.g. the iTunes/Samples artwork already comes back
 * at a fixed, reasonable size, so there's nothing to rewrite there).
 */
object ThumbnailUtils {

    // Matches a trailing Google image-CDN size directive, e.g. "=w120-h120-l90-rj"
    // or "=s120-c-k-c0x00ffffff-no-rj". Everything after the leading "=" is
    // replaced, so any crop/no-crop flags reset to a plain square render.
    private val SIZE_DIRECTIVE = Regex("=w\\d+-h\\d+(-.*)?$|=s\\d+(-.*)?$")

    /**
     * Common sizes used across the app - keeps callers from picking arbitrary
     * numbers and gives Coil's memory cache a small, stable set of URLs to
     * de-dupe against instead of a unique URL per pixel size on screen.
     */
    object Size {
        const val LIST_ROW = 200   // 44-64dp list thumbnails on ~3x density screens
        const val CARD = 320       // Home's larger recommendation cards
        const val PLAYER = 1024    // Full-width Now Playing artwork
    }

    /**
     * Returns [url] rewritten to request a [size]x[size] render, or [url]
     * unchanged if it isn't a Google CDN thumbnail this trick applies to.
     */
    fun resized(url: String?, size: Int): String? {
        if (url.isNullOrBlank()) return url
        if ("googleusercontent.com" !in url && "ggpht.com" !in url) return url

        return if (SIZE_DIRECTIVE.containsMatchIn(url)) {
            SIZE_DIRECTIVE.replace(url, "=w$size-h$size-l90-rj")
        } else {
            // No existing size directive to swap - append one.
            url.trimEnd('/') + "=w$size-h$size-l90-rj"
        }
    }
}
