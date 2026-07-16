package com.example.player

/**
 * PlaybackService ka ExoPlayer sirf EK media item ke saath kaam karta hai
 * (poori playlist advance nahi karta - agla/pichla track queue+autoplay logic
 * se app ke andar decide hota hai), isliye ExoPlayer ke apne timeline mein
 * kabhi "next item" hota hi nahi - Media3 isi wajah se phone ke control
 * center / lock screen mein "Next" button ko automatically disable/hide kar
 * deta tha (sirf "Previous" dikhta tha, jo asal mein current gaana hi
 * restart karta tha).
 *
 * Fix: PlaybackService apne MediaSession.Callback mein next/previous command
 * ko force-enable karta hai, aur press hone par yahin registered callbacks
 * (MusicPlayer.skipNext / skipPrevious) ko call karta hai - taaki system UI
 * ka next/previous bhi wahi real queue+autoplay logic use kare jo app ke andar
 * ke buttons use karte hain.
 */
object PlaybackBridge {
    var onNext: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
    // Control-center/lock-screen se seek bar drag hone par yeh asli
    // MusicPlayer.seekTo() ko call karta hai.
    var onSeek: ((Long) -> Unit)? = null
}

