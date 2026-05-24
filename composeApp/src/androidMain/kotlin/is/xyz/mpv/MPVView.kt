package `is`.xyz.mpv

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

// Minimal surface wrapper — MPVLib lifecycle (create/init/destroy) is managed externally.
class MPVView(context: Context, attrs: AttributeSet?) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    fun initialize() {
        holder.addCallback(this)
    }

    fun destroy() {
        holder.removeCallback(this)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        MPVLib.attachSurface(holder.surface)
        MPVLib.setOptionString("force-window", "yes")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        MPVLib.detachSurface()
    }
}
