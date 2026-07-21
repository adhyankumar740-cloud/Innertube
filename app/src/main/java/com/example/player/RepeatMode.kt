package com.example.player

/**
 * Repeat behavior for the main queue - mirrors Media3's three
 * Player.REPEAT_MODE_* states (OFF/ALL/ONE) so MusicPlayer can translate
 * directly to/from the underlying ExoPlayer instance.
 *
 * Cycle order (see MusicPlayer.cycleRepeatMode()): OFF -> ALL -> ONE -> OFF.
 */
enum class RepeatMode { OFF, ALL, ONE }

