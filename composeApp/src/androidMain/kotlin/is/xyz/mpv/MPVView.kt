package `is`.xyz.mpv

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

class MPVView(context: Context, attrs: AttributeSet?) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    // Guards against surfaceCreated firing before MPVLib.init() runs.
    @Volatile private var mpvReady = false

    init {
        holder.addCallback(this)
    }

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
