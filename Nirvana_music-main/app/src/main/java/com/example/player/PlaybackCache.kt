package com.example.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
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

    // InnerTube-resolved stream URLs are pre-signed googlevideo.com CDN links -
    // no auth header needed (unlike the old relay's /audio endpoint).
    private fun upstreamHttpDataSourceFactory(): DefaultHttpDataSource.Factory {
        return DefaultHttpDataSource.Factory()
    }

    // ExoPlayer isi factory se media source banata hai - cache-through
    // (pehle cache check, miss hone par upstream se fetch karke cache me
    // likh deta hai).
    fun cacheDataSourceFactory(context: Context): CacheDataSource.Factory {
        return CacheDataSource.Factory()
            .setCache(get(context))
            .setUpstreamDataSourceFactory(upstreamHttpDataSourceFactory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
