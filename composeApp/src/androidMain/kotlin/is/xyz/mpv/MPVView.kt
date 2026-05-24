package `is`.xyz.mpv

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

// Minimal surface wrapper — MPVLib lifecycle (create/init/destroy) is managed externally.
class MPVView(context: Context, attrs: AttributeSet?) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    // Set to true after MPVLib.init() has been called. Guards against surfaceCreated
    // firing before MPV is ready (which happens when the SurfaceView enters the window
    // during the same frame that DisposableEffect runs).
    @Volatile private var mpvReady = false

    init {
        holder.addCallback(this)
    }

    // Call this immediately after MPVLib.init(). If the surface was already created
    // while MPV was still initializing, attach it now.
    fun onMpvInit() {
        mpvReady = true
        val surface = holder.surface
        if (surface != null && surface.isValid) {
            MPVLib.attachSurface(surface)
            MPVLib.setOptionString("force-window", "yes")
        }
    }

    fun destroy() {
        mpvReady = false
        holder.removeCallback(this)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!mpvReady) return
        MPVLib.attachSurface(holder.surface)
        MPVLib.setOptionString("force-window", "yes")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!mpvReady) return
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        MPVLib.detachSurface()
    }
}
