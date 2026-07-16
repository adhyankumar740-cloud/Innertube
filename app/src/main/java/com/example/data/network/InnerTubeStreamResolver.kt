package com.example.data.network

import android.content.Context
import android.net.ConnectivityManager
import com.example.data.youtube.AudioQuality
import com.example.data.youtube.YTPlayerUtils

/**
 * Resolves a directly-playable audio stream URL for a YouTube video id, fully
 * on-device - no relay/render server, no YouTube Data API key. Runs the same
 * cipher-deobfuscation + PoToken + multi-client fallback pipeline Metrolist
 * Music uses (see [YTPlayerUtils]), so this is resilient the same way: if
 * YouTube's main web client stream is throttled/blocked, it automatically
 * falls through a dozen other clients before giving up.
 */
object InnerTubeStreamResolver {

    /**
     * @throws Exception if every client in the fallback chain failed to
     * produce a playable format - callers should catch this the same way
     * they used to catch RelayService.resolve() failures.
     */
    suspend fun resolveStreamUrl(context: Context, videoId: String): String {
        val connectivityManager =
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val playbackData = YTPlayerUtils.playerResponseForPlayback(
            videoId = videoId,
            audioQuality = AudioQuality.AUTO,
            connectivityManager = connectivityManager
        ).getOrThrow()
        return playbackData.streamUrl
    }
}
