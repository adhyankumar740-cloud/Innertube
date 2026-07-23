package com.example.player

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/**
 * "NEVER GO AWAY" FIX: Media3's [DefaultMediaNotificationProvider] only marks the media
 * notification as `ongoing` (i.e. not swipe-dismissible, the way an active call or download
 * notification behaves) while something is actually playing - the moment playback pauses (or
 * the queue runs out) it flips to a normal dismissible notification, which is exactly what was
 * making the "This phone" media card disappear from the notification shade/quick settings.
 *
 * [DefaultMediaNotificationProvider.createNotification] itself is `final` and can't be
 * overridden, but [addNotificationActions] hands us the actual [NotificationCompat.Builder]
 * before the notification is built - forcing `setOngoing(true)` there, unconditionally, is
 * enough to keep it pinned regardless of play/pause state. Wired in from
 * MusicService.onCreate() in place of a plain DefaultMediaNotificationProvider.
 */
@UnstableApi
class AlwaysOngoingMediaNotificationProvider(
    context: Context,
    notificationIdProvider: () -> Int,
    channelId: String,
    channelNameResourceId: Int
) : DefaultMediaNotificationProvider(context, notificationIdProvider, channelId, channelNameResourceId) {

    override fun addNotificationActions(
        mediaSession: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        builder: NotificationCompat.Builder,
        actionFactory: MediaNotification.ActionFactory
    ): IntArray {
        builder.setOngoing(true)
        return super.addNotificationActions(mediaSession, mediaButtons, builder, actionFactory)
    }
}
