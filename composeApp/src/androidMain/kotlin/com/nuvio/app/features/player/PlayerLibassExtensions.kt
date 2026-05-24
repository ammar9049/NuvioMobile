package com.nuvio.app.features.player

// Kept for backwards-compatible settings storage; MPV renders ASS/SSA natively via its built-in
// libass, so this enum no longer drives any playback logic on Android.
enum class LibassRenderType {
    CUES,
    EFFECTS_CANVAS,
    EFFECTS_OPEN_GL,
    OVERLAY_CANVAS,
    OVERLAY_OPEN_GL,
}
