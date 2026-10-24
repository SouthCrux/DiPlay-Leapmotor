package com.shilapi.xcertplay.ultra

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.UltraLocalSettingsInput
import org.json.JSONObject
import kotlin.math.hypot

/** Routes only gestures started on a fully opaque Settings image in an accepted Canvas.
 * This is output-submission evidence, not confirmation of physical display presentation.
 * A hidden/replaced image cancels its action but keeps ownership until the finger is released.
 */
internal class UltraVehiclePanelTouchRouter(private val tap: (Long, Float, Float) -> Boolean) {
    private var candidateBitmap: Bitmap? = null
    private var candidate: Target? = null
    private var presented: Target? = null
    private var revision = 0L
    private var gesture = false
    private var local = false
    private var canceled = false
    private var pressed: Target? = null
    private var pressedRevision = 0L
    private var startX = 0f
    private var startY = 0f
    private var pointerId = -1

    fun drawn(bitmap: Bitmap, scene: JSONObject) {
        val ticket = synchronized(this) { revision }
        // Traverse on the composition worker without holding the monitor used by pause/touch.
        val target = runCatching { Target.from(scene) }.getOrNull()
        synchronized(this) {
            if (ticket != revision) return
            candidateBitmap = bitmap
            candidate = target
        }
    }

    @Synchronized fun submitted(bitmap: Bitmap) {
        // A stale/unknown output must never establish input eligibility.
        presented = candidate.takeIf { candidateBitmap === bitmap && !bitmap.isRecycled }
        candidateBitmap = null
        candidate = null
    }

    @Synchronized fun invalidate() {
        revision++
        candidateBitmap = null
        candidate = null
        presented = null
        if (gesture) canceled = true
    }

    /** Normalized contacts for the complete resource canvas. True owns the whole sequence. */
    @Synchronized fun touch(contacts: List<AirPlayContact>, cancel: Boolean = false): Boolean {
        val down = contacts.any { it.down }
        val point = contacts.singleOrNull()
        val valid = point != null && point.x.isFinite() && point.y.isFinite() &&
            point.x in 0.0..1.0 && point.y in 0.0..1.0
        if (!gesture) {
            if (!down) return false
            gesture = true
            val target = presented
            val xy = if (valid && target != null) target.map(point!!.x, point.y) else null
            local = valid && target?.protects(point!!.x,point.y) == true
            canceled = !local || xy == null
            pressed = target.takeIf { local }
            pressedRevision = revision
            pointerId = point?.id ?: -1
            if (xy != null) { startX = xy.first; startY = xy.second }
        }
        val consumed = local
        if (cancel) canceled = true
        if (local) {
            val target = pressed
            val current = presented
            val xy = if (valid && target != null) target.map(point!!.x, point.y) else null
            if (target == null || current != target || point?.id != pointerId || revision != pressedRevision ||
                xy == null || hypot(xy.first - startX, xy.second - startY) > TAP_SLOP) canceled = true
            if (!down && !canceled && xy != null && target != null) {
                // The producer independently rejects retired tokens and changed model/page state.
                tap(target.token, xy.first, xy.second)
            }
        }
        if (!down) { gesture = false; local = false; pressed = null; canceled = false }
        return consumed
    }

    private data class Target(val token: Long, val outputWidth: Float, val outputHeight: Float,
        val image: RectF, val visible: RectF, val occluders: List<RectF>,
        val protectedAreas: List<RectF>, val actionable: Boolean) {
        fun protects(x: Double,y: Double): Boolean = protectedAreas.any {
            it.contains((x * outputWidth).toFloat(),(y * outputHeight).toFloat())
        }
        fun map(x: Double, y: Double): Pair<Float, Float>? {
            val px = (x * outputWidth).toFloat(); val py = (y * outputHeight).toFloat()
            if (!actionable || !visible.contains(px, py) || occluders.any { it.contains(px, py) }) return null
            return (px - image.left) * UltraLocalSettingsInput.WIDTH / image.width() to
                (py - image.top) * UltraLocalSettingsInput.HEIGHT / image.height()
        }

        companion object {
            /** Mirrors rectangular clipping and affine placement of UltraSceneRenderer. Rotated or
             * translucent Settings images are ineligible. Later paint conservatively occludes input,
             * including an entire clip for shapes whose path may extend outside their layer bounds.
             */
            fun from(scene: JSONObject): Target? {
                fun number(o: JSONObject, key: String) = o.getDouble(key).toFloat().also { require(it.isFinite()) }
                fun rect(node: JSONObject): RectF {
                    val b = node.getJSONObject("bounds")
                    val origin = b.getJSONObject("origin"); val size = b.getJSONObject("size")
                    val x = number(origin,"x"); val y = number(origin,"y")
                    val w = number(size,"width"); val h = number(size,"height")
                    require(w >= 0 && h >= 0)
                    return RectF(x,y,x+w,y+h)
                }
                val root = rect(scene)
                require(root.left == 0f && root.top == 0f && root.width() > 0 && root.height() > 0)
                var target: Target? = null
                var settingsCount = 0
                val occluders = mutableListOf<RectF>()
                val protectedAreas = mutableListOf<RectF>()
                var nodes = 0
                fun paint(area: RectF) { if (!area.isEmpty && target != null) occluders += RectF(area) }
                fun visit(node: JSONObject, parent: Matrix, parentClip: RectF, parentOpacity: Float,
                    rectangularClip: Boolean, depth: Int) {
                    require(++nodes <= 4096 && depth <= 64)
                    if (node.getBoolean("hidden")) return
                    val opacity = parentOpacity * number(node,"opacity")
                    if (opacity <= 0f) return
                    val b = rect(node)
                    val pos = node.getJSONObject("position"); val anchor = node.getJSONObject("anchorPoint")
                    val t = node.getJSONObject("transform")
                    val matrix = Matrix(parent).apply {
                        preTranslate(number(pos,"x"), number(pos,"y"))
                        preConcat(Matrix().apply { setValues(floatArrayOf(number(t,"a"), number(t,"c"), number(t,"tx"),
                            number(t,"b"), number(t,"d"), number(t,"ty"),0f,0f,1f)) })
                        preTranslate(-number(anchor,"x") * b.width() - b.left,
                            -number(anchor,"y") * b.height() - b.top)
                    }
                    val area = RectF(b).also { matrix.mapRect(it) }
                    val values = FloatArray(9).also(matrix::getValues)
                    val axisAligned = values[1] == 0f && values[3] == 0f && values[0] > 0f && values[4] > 0f
                    val clip = RectF(parentClip)
                    if (node.getBoolean("maskToBounds") && !clip.intersect(area)) return
                    val exactClip = rectangularClip && (!node.getBoolean("maskToBounds") || axisAligned)
                    val drawn = RectF(area)
                    if (!drawn.intersect(clip)) drawn.setEmpty()
                    if (number(node.getJSONObject("backgroundColor"),"a") > 0) paint(drawn)
                    if (!node.isNull("gradient")) {
                        val stops=node.getJSONObject("gradient").getJSONArray("stops")
                        if ((0 until stops.length()).any { number(stops.getJSONObject(it).getJSONObject("color"),"a") > 0 }) paint(drawn)
                    }
                    if (!node.isNull("shape")) {
                        val shape=node.getJSONObject("shape")
                        if (shape.getJSONArray("commands").length() > 0 &&
                            (number(shape.getJSONObject("fillColor"),"a") > 0 ||
                                number(shape,"lineWidth") > 0 && number(shape.getJSONObject("strokeColor"),"a") > 0 &&
                                number(shape,"strokeEnd") > number(shape,"strokeStart"))) paint(clip)
                    }
                    if (!node.isNull("video")) {
                        val v = node.getJSONObject("video")
                        if (v.getBoolean("active") && !v.isNull("token")) {
                            if (v.getString("identifier") == UltraLocalSettingsInput.IDENTIFIER) {
                                settingsCount++
                                // Axis-aligned positive transforms only; skewed ancestor clipping is
                                // deliberately ineligible rather than approximated for touch.
                                if (!drawn.isEmpty) {
                                    protectedAreas += RectF(drawn)
                                    occluders.clear()
                                    target = Target(v.getLong("token"),root.width(),root.height(),area,drawn,occluders,
                                        protectedAreas,opacity == 1f && axisAligned && exactClip)
                                }
                            } else paint(drawn)
                        }
                    }
                    if (!node.isNull("texture") && number(node.getJSONObject("tint"),"a") > 0) paint(drawn)
                    val children = node.getJSONArray("children")
                    for (i in 0 until children.length()) visit(children.getJSONObject(i),matrix,clip,opacity,exactClip,depth+1)
                }
                visit(scene, Matrix(), root, 1f, true, 0)
                return target?.let { it.copy(occluders=occluders.toList(),protectedAreas=protectedAreas.toList(),
                    actionable=it.actionable && settingsCount == 1) }
            }
        }
    }

    companion object { private const val TAP_SLOP = 24f }
}
