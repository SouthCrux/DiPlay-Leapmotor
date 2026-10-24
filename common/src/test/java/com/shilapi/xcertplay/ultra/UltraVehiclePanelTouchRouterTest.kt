package com.shilapi.xcertplay.ultra

import android.graphics.Bitmap
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.UltraLocalSettingsInput
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UltraVehiclePanelTouchRouterTest {
    @Test fun invalidationDoesNotWaitForSceneTraversalAndRejectsItsLateCandidate() = Fixture().use { f ->
        val entered=CountDownLatch(1); val release=CountDownLatch(1); val invalidated=CountDownLatch(1)
        val errors=AtomicReference<Throwable>()
        val gated=object : JSONObject(scene().toString()) {
            override fun getJSONObject(name: String): JSONObject {
                if (name == "bounds") {
                    entered.countDown()
                    check(release.await(5,TimeUnit.SECONDS))
                }
                return super.getJSONObject(name)
            }
        }
        val drawing=thread {
            try { f.router.drawn(f.bitmap,gated) } catch (error: Throwable) { errors.set(error) }
        }
        var pause: Thread? = null
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            pause=thread { f.router.invalidate(); invalidated.countDown() }
            assertTrue("Pausing must not wait for scene traversal",invalidated.await(2,TimeUnit.SECONDS))
        } finally {
            release.countDown(); drawing.join(5_000); pause?.join(5_000)
        }
        assertFalse(drawing.isAlive); assertNull(errors.get())
        f.router.submitted(f.bitmap)
        assertFalse(f.router.touch(listOf(contact())))
        assertFalse(f.router.touch(listOf(contact(down=false))))
    }
    private fun layer(x: Double = 0.0, y: Double = 0.0, w: Int = 1920, h: Int = 720) = JSONObject().apply {
        put("bounds", JSONObject().put("origin",point(0.0,0.0)).put("size",JSONObject().put("width",w).put("height",h)))
        put("position",point(x,y)); put("anchorPoint",point(0.0,0.0))
        put("transform",JSONObject().put("a",1).put("b",0).put("c",0).put("d",1).put("tx",0).put("ty",0))
        put("hidden",false); put("maskToBounds",false); put("opacity",1)
        put("backgroundColor",JSONObject().put("a",0)); put("children",JSONArray())
    }
    private fun point(x: Double,y: Double) = JSONObject().put("x",x).put("y",y)
    private fun settings(token: Long = 7) = layer(135.0,w=1650).apply {
        put("video",JSONObject().put("identifier",UltraLocalSettingsInput.IDENTIFIER).put("active",true).put("token",token))
    }
    private fun scene(node: JSONObject = settings(), above: JSONObject? = null) = layer().apply {
        put("children",JSONArray().put(node).apply { above?.let(::put) })
    }
    private fun contact(x: Double=960.0,y: Double=360.0,down: Boolean=true,id: Int=0) =
        AirPlayContact(id,x/1920,y/720,down)
    private class Fixture : AutoCloseable {
        val bitmap = Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
        val taps = mutableListOf<Triple<Long,Float,Float>>()
        val router = UltraVehiclePanelTouchRouter { t,x,y -> taps += Triple(t,x,y); true }
        fun publish(scene: JSONObject) { router.drawn(bitmap,scene); router.submitted(bitmap) }
        override fun close() { router.invalidate(); bitmap.recycle() }
    }

    @Test fun onlyAnAcceptedMatchingCanvasEnablesImageLocalTaps() = Fixture().use { f ->
        f.router.drawn(f.bitmap,scene())
        assertFalse(f.router.touch(listOf(contact())))
        assertFalse(f.router.touch(listOf(contact(down=false))))
        f.router.submitted(f.bitmap)
        assertTrue(f.router.touch(listOf(contact())))
        assertTrue(f.router.touch(listOf(contact(down=false))))
        assertEquals(listOf(Triple(7L,825f,360f)),f.taps)
    }

    @Test fun hiddenTranslucentRotatedMissingOrDuplicateTargetsAreIneligible() {
        val hidden=settings().put("hidden",true)
        val transparent=settings().put("opacity",0.5)
        val rotated=settings().also { it.getJSONObject("transform").put("b",0.2) }
        val duplicate=scene().also { it.getJSONArray("children").put(settings(8)) }
        val missing=layer()
        for ((s,consumed) in listOf(scene(hidden) to false,scene(transparent) to true,
            scene(rotated) to true,duplicate to true,missing to false)) Fixture().use { f ->
            f.publish(s)
            assertEquals(consumed,f.router.touch(listOf(contact())))
            assertEquals(consumed,f.router.touch(listOf(contact(down=false))))
            assertTrue(f.taps.isEmpty())
        }
    }

    @Test fun laterPaintOccludesOnlyItsCoveredArea() = Fixture().use { f ->
        val cover=layer(900.0,300.0,120,120).apply { getJSONObject("backgroundColor").put("a",1) }
        f.publish(scene(above=cover))
        assertTrue(f.router.touch(listOf(contact())))
        assertTrue(f.router.touch(listOf(contact(down=false))))
        assertTrue(f.taps.isEmpty())
        assertTrue(f.router.touch(listOf(contact(500.0))))
        assertTrue(f.router.touch(listOf(contact(500.0,down=false))))
        assertEquals(listOf(Triple(7L,365f,360f)),f.taps)
    }

    @Test fun parentClippingAndScaleMatchThePaintedImage() = Fixture().use { f ->
        val parent=layer(200.0,100.0,300,300).apply {
            put("maskToBounds",true)
            getJSONObject("transform").put("a",0.5).put("d",0.5)
            put("children",JSONArray().put(settings().put("position",point(0.0,0.0))))
        }
        f.publish(scene(parent))
        assertTrue(f.router.touch(listOf(contact(250.0,150.0))))
        assertTrue(f.router.touch(listOf(contact(250.0,150.0,down=false))))
        assertEquals(listOf(Triple(7L,100f,100f)),f.taps)
        assertFalse(f.router.touch(listOf(contact(400.0,150.0))))
        assertFalse(f.router.touch(listOf(contact(400.0,150.0,down=false))))
    }

    @Test fun aPhoneGestureIsNeverStolenWhenItMovesIntoTheVehiclePanel() = Fixture().use { f ->
        f.publish(scene())
        assertFalse(f.router.touch(listOf(contact(10.0))))
        assertFalse(f.router.touch(listOf(contact())))
        assertFalse(f.router.touch(listOf(contact(down=false))))
        assertTrue(f.taps.isEmpty())
    }

    @Test fun invalidationCancelsTheActionButSwallowsTheLocalRelease() = Fixture().use { f ->
        f.publish(scene())
        assertTrue(f.router.touch(listOf(contact())))
        f.router.invalidate()
        assertTrue(f.router.touch(listOf(contact(down=false))))
        assertTrue(f.taps.isEmpty())
        assertFalse(f.router.touch(listOf(contact())))
        assertFalse(f.router.touch(listOf(contact(down=false))))
    }

    @Test fun replacedImageOrPlacementCancelsTheTapWithoutLeakingItsRelease() {
        for (next in listOf(scene(settings(8)),scene(settings().put("position",point(140.0,0.0))))) Fixture().use { f ->
            f.publish(scene())
            assertTrue(f.router.touch(listOf(contact())))
            f.publish(next)
            assertTrue(f.router.touch(listOf(contact(down=false))))
            assertTrue(f.taps.isEmpty())
        }
    }

    @Test fun dragMultitouchChangedPointerCancelAndInvalidCoordinatesCannotTap() {
        val changes=listOf(listOf(contact(1100.0)),listOf(contact(),contact(970.0,id=1)),
            listOf(contact(id=1)),emptyList(),listOf(AirPlayContact(0,Double.NaN,0.5,true)))
        for (change in changes) Fixture().use { f ->
            f.publish(scene())
            assertTrue(f.router.touch(listOf(contact())))
            assertTrue(f.router.touch(change))
            f.router.touch(listOf(contact(down=false)))
            assertTrue(f.taps.isEmpty())
        }
    }

    @Test fun anOldCandidateCannotBecomeInteractiveAfterInvalidationOrUnknownSubmission() = Fixture().use { f ->
        f.router.drawn(f.bitmap,scene())
        f.router.invalidate(); f.router.submitted(f.bitmap)
        assertFalse(f.router.touch(listOf(contact())))
        assertFalse(f.router.touch(listOf(contact(down=false))))
        f.publish(scene())
        val other=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
        try { f.router.submitted(other) } finally { other.recycle() }
        assertFalse(f.router.touch(listOf(contact())))
        assertFalse(f.router.touch(listOf(contact(down=false))))
    }

    @Test fun malformedScenesAndRotatedAncestorClipsDoNotInventTouchAreas() {
        val parent=layer().apply {
            put("maskToBounds",true); getJSONObject("transform").put("b",1)
            val child=settings().apply { getJSONObject("transform").put("b",-1) }
            put("children",JSONArray().put(child))
        }
        for ((s,consumed) in listOf(JSONObject() to false,scene(parent) to true)) Fixture().use { f ->
            f.publish(s)
            assertEquals(consumed,f.router.touch(listOf(contact())))
            assertEquals(consumed,f.router.touch(listOf(contact(down=false))))
            assertTrue(f.taps.isEmpty())
        }
    }

    @Test fun anAndroidCancelIsNeverInterpretedAsATap() = Fixture().use { f ->
        f.publish(scene())
        assertTrue(f.router.touch(listOf(contact())))
        assertTrue(f.router.touch(listOf(contact(down=false)),cancel=true))
        assertTrue(f.taps.isEmpty())
    }

    @Test fun demonstrablyEmptyOrTransparentPaintDoesNotBlockTheVisiblePanel() {
        val gradient=layer().put("gradient",JSONObject().put("stops",JSONArray()))
        val texture=layer().put("texture",JSONObject()).put("tint",JSONObject().put("a",0))
        val shape=layer().put("shape",JSONObject().put("commands",JSONArray().put(JSONObject()))
            .put("fillColor",JSONObject().put("a",0)).put("strokeColor",JSONObject().put("a",0))
            .put("lineWidth",0).put("strokeStart",0).put("strokeEnd",1))
        for (above in listOf(gradient,texture,shape)) Fixture().use { f ->
            f.publish(scene(above=above))
            assertTrue(f.router.touch(listOf(contact())))
            assertTrue(f.router.touch(listOf(contact(down=false))))
            assertEquals(1,f.taps.size)
        }
    }
}
