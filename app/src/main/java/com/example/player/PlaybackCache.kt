package com.example.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.metrolist.innertube.YouTube
import okhttp3.OkHttpClient
import java.io.File

// Ek hi on-disk audio cache poore app ke liye:
//  - PlaybackService ka ExoPlayer isi cache se hoke normal playback read/write
//    karta hai (progressive download ke chunks disk pe save hote jaate hain
//    jaise-jaise gaana bajta hai)
//  - MusicPlayer ka background prefetcher (preloadNextTrack) bhi *isi* cache
//    me agle gaane ke audio chunks pehle se utaar leta hai, current gaana
//    chalte-chalte
// Dono ek hi Cache instance share karte hai isliye jab agla track actually
// play hota hai, uske bytes pehle se disk pe milte hai - ExoPlayer network
// wait kiye bina seedha cache se serve kar deta hai, aur buffering sirf
// pehli baar (app open/pehla gaana) hoti hai, baar baar nahi.
object PlaybackCache {

    private const val MAX_CACHE_BYTES = 300L * 1024 * 1024 // 300 MB rolling cache, LRU evict

    @Volatile private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache {
        return cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(context.applicationContext.cacheDir, "relay_audio_cache"),
                LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
                StandaloneDatabaseProvider(context.applicationContext)
            ).also { cache = it }
        }
    }

    // InnerTube-resolved stream URLs are pre-signed googlevideo.com CDN links - no auth header
    // needed (unlike the old relay's /audio endpoint). This MUST go through OkHttp, not
    // DefaultHttpDataSource (HttpURLConnection) - googlevideo.com's CDN frequently buffers then
    // fails ("source error") on the plain HttpURLConnection-based data source, which is exactly
    // why MetroList's own MusicService.createCacheDataSource() uses OkHttpDataSource here too.
    // Also honors YouTube.proxy like every other network call in this app (PlayerJsFetcher,
    // PoTokenWebView, YTPlayerUtils) so a configured proxy covers the actual audio fetch too,
    // not just the API calls that find the stream URL in the first place.
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(YouTube.proxy)
            .build()
    }

    private fun upstreamHttpDataSourceFactory(context: Context): DataSource.Factory {
        return DefaultDataSource.Factory(context, OkHttpDataSource.Factory(okHttpClient))
    }

    // ExoPlayer isi factory se media source banata hai - cache-through
    // (pehle cache check, miss hone par upstream se fetch karke cache me
    // likh deta hai).
    fun cacheDataSourceFactory(context: Context): CacheDataSource.Factory {
        return CacheDataSource.Factory()
            .setCache(get(context))
            .setUpstreamDataSourceFactory(upstreamHttpDataSourceFactory(context))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
