package com.shilapi.xcertplay.ultra

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UltraSceneRendererTest {
    private fun node(x: Int = 0, y: Int = 0, w: Int = 40, h: Int = 30, color: Int = Color.TRANSPARENT) = JSONObject().apply {
        put("bounds", JSONObject().put("origin", point(0, 0)).put("size", JSONObject().put("width", w).put("height", h)))
        put("position", point(x, y)); put("anchorPoint", point(0, 0))
        put("transform", JSONObject().put("a", 1).put("b", 0).put("c", 0).put("d", 1).put("tx", 0).put("ty", 0))
        put("backgroundColor", rgba(color)); put("opacity", 1); put("hidden", false); put("maskToBounds", false)
        put("children", JSONArray()); put("tint", rgba(Color.WHITE))
    }
    private fun point(x: Int, y: Int) = JSONObject().put("x", x).put("y", y)
    private fun rgba(color: Int) = JSONObject().put("r", Color.red(color) / 255.0).put("g", Color.green(color) / 255.0)
        .put("b", Color.blue(color) / 255.0).put("a", Color.alpha(color) / 255.0)
    private fun JSONObject.add(child: JSONObject) = apply { getJSONArray("children").put(child) }
    private fun draw(scene: JSONObject, load: (String) -> ByteArray = { error("No texture expected") }) = UltraSceneRenderer().render(scene, load)

    @Test fun latchedVideoUsesTheExactInputTokenAndDoesNotRecycleBorrowedPixels() {
        val video = node(4, 3, 8, 6).put("opacity", 0.5).put("video", JSONObject()
            .put("identifier", "phone-video").put("active", true).put("token", 37).put("pts", 12.125))
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).useBitmap { frame ->
            frame.eraseColor(Color.RED)
            val output = UltraSceneRenderer().render(node(color = Color.BLACK).add(video), { error("No PNG expected") },
                UltraVideoFrameSource { identifier, token, pts ->
                    assertEquals("phone-video", identifier); assertEquals(37L, token); assertEquals(12.125, pts, 0.0)
                    frame
                })
            output.useBitmap {
                assertTrue(Color.red(it.getPixel(7, 5)) in 127..128)
                assertEquals(Color.BLACK, it.getPixel(2, 2))
            }
            assertFalse(frame.isRecycled)
        }
        assertThrows(IllegalStateException::class.java) { draw(node().add(video)) }
    }

    @Test fun nestedPositionAnchorAndBoundsOriginComposeInLocalCoordinates() {
        val root = node(color = Color.BLACK)
        val parent = node(20, 15, 20, 10, Color.RED).put("anchorPoint", JSONObject().put("x", 0.5).put("y", 0.5))
        parent.getJSONObject("bounds").put("origin", point(3, 4))
        parent.add(node(5, 6, 4, 3, Color.BLUE))
        draw(root.add(parent)).useBitmap {
            assertEquals(Color.RED, it.getPixel(11, 11))
            assertEquals(Color.BLUE, it.getPixel(13, 13))
            assertEquals(Color.BLACK, it.getPixel(9, 9))
        }
    }

    @Test fun clippingVisibilityAndSiblingOrderAreApplied() {
        val root = node(color = Color.BLACK)
        val clipped = node(5, 5, 10, 10, Color.RED).put("maskToBounds", true)
            .add(node(5, 5, 20, 10, Color.BLUE))
        root.add(clipped).add(node(12, 12, 4, 4, Color.GREEN)).add(node(color = Color.WHITE).put("hidden", true))
        draw(root).useBitmap {
            assertEquals(Color.BLUE, it.getPixel(11, 11))
            assertEquals(Color.GREEN, it.getPixel(13, 13))
            assertEquals(Color.BLACK, it.getPixel(20, 11))
        }
    }

    @Test fun inheritedOpacityAndAffineRotationUseActualPixels() {
        val root = node(color = Color.BLACK)
        val parent = node().put("opacity", 0.5)
        val rotated = node(15, 5, 10, 5, Color.RED)
        rotated.put("transform", JSONObject().put("a", 0).put("b", 1).put("c", -1).put("d", 0).put("tx", 0).put("ty", 0))
        draw(root.add(parent.add(rotated))).useBitmap {
            assertTrue(Color.red(it.getPixel(12, 8)) in 127..128)
            assertEquals(Color.BLACK, it.getPixel(17, 8))
        }
    }

    @Test fun pngIsDecodedTintedAndCachedOnce() {
        val png = ByteArrayOutputStream().use { out ->
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).useBitmap {
                it.eraseColor(Color.WHITE); it.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            out.toByteArray()
        }
        fun image(x: Int) = node(x, 2, 6, 6).put("texture", JSONObject().put("path", "r/test.png").put("width", 2).put("height", 2))
            .put("tint", rgba(Color.BLUE))
        var loads = 0
        draw(node(color = Color.BLACK).add(image(2)).add(image(12))) { name ->
            assertEquals("r/test.png", name); loads++; png
        }.useBitmap {
            assertEquals(Color.BLUE, it.getPixel(4, 4)); assertEquals(Color.BLUE, it.getPixel(14, 4))
        }
        assertEquals(1, loads)
    }

    @Test fun malformedGeometryPathsAndImagesFailBeforeOutput() {
        assertThrows(IllegalArgumentException::class.java) { draw(node(w = 8192)) }
        assertThrows(IllegalArgumentException::class.java) { draw(node().put("opacity", 2)) }
        assertThrows(IllegalArgumentException::class.java) { draw(node().put("opacity", "1")) }
        val image = node().put("texture", JSONObject().put("path", "../private.png").put("width", 2).put("height", 2))
        assertThrows(IllegalArgumentException::class.java) { draw(image) { error("Unsafe path reached loader") } }
        image.getJSONObject("texture").put("path", "r/test.png")
        assertThrows(IllegalArgumentException::class.java) { draw(image) { ByteArray(32) } }
    }

    private fun png(color: Int): ByteArray = ByteArrayOutputStream().use { out ->
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).useBitmap {
            it.eraseColor(color); it.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        out.toByteArray()
    }

    private fun image(name: String, x: Int = 0) = node(x, 0, 2, 2)
        .put("texture", JSONObject().put("path", name).put("width", 2).put("height", 2))

    @Test fun connectionCacheReusesTexturesAcrossFramesAndReleasesOnlyItsOwnPixels() {
        val bytes = png(Color.RED)
        var loads = 0
        val renderer = UltraSceneRenderer(textureCacheBytes = 16)
        val first = renderer.render(image("r/a.png")) { loads++; bytes }
        renderer.render(image("r/a.png")) { loads++; bytes }.useBitmap { assertEquals(Color.RED, it.getPixel(0, 0)) }
        assertEquals(1, loads)
        assertEquals(16L, renderer.retainedTextureBytes)
        renderer.close(); renderer.close()
        assertEquals(0L, renderer.retainedTextureBytes)
        first.useBitmap { assertEquals(Color.RED, it.getPixel(0, 0)) }
        assertThrows(IllegalStateException::class.java) { renderer.render(image("r/a.png")) { bytes } }
        UltraSceneRenderer(textureCacheBytes = 16).use { next ->
            next.render(image("r/a.png")) { loads++; png(Color.BLUE) }.useBitmap {
                assertEquals(Color.BLUE, it.getPixel(0, 0))
            }
        }
        assertEquals(2, loads)
    }

    @Test fun cacheEvictionWaitsUntilTheWholeFrameHasFinishedUsingItsTextures() {
        val bytes = png(Color.WHITE)
        val loads = mutableListOf<String>()
        UltraSceneRenderer(textureCacheBytes = 16).use { renderer ->
            fun render(scene: JSONObject) = renderer.render(scene) { name -> loads += name; bytes }
            render(image("r/a.png")).recycle()
            // a is used again after b: recycling a during traversal would corrupt this frame.
            render(node().add(image("r/a.png")).add(image("r/b.png", 3)).add(image("r/a.png", 6)))
                .useBitmap { assertEquals(Color.WHITE, it.getPixel(6, 0)) }
            assertEquals(listOf("r/a.png", "r/b.png"), loads)
            assertTrue(renderer.retainedTextureBytes <= 16)
            render(image("r/a.png")).recycle()
            assertEquals(listOf("r/a.png", "r/b.png", "r/a.png"), loads)
        }
    }

    @Test fun cachedDimensionsAreStillValidatedAndFailedScenesDoNotRetainNewImages() {
        val bytes = png(Color.WHITE)
        UltraSceneRenderer(textureCacheBytes = 32).use { renderer ->
            renderer.render(image("r/a.png")) { bytes }.recycle()
            val mismatch = image("r/a.png").apply { getJSONObject("texture").put("width", 3) }
            assertThrows(IllegalArgumentException::class.java) { renderer.render(mismatch) { error("Cache hit must validate") } }
            val invalid = node().add(image("r/b.png")).add(node().put("hidden", "invalid"))
            assertThrows(IllegalArgumentException::class.java) { renderer.render(invalid) { bytes } }
            assertEquals(16L, renderer.retainedTextureBytes)
        }
    }

    @Test fun recursionAndNodeBudgetsFailWithoutAllocatingUnboundedOutput() {
        val deep = node(); var current = deep
        repeat(65) { val next = node(); current.add(next); current = next }
        assertThrows(IllegalArgumentException::class.java) { draw(deep) }
        val broad = node(); repeat(4096) { broad.add(node()) }
        assertThrows(IllegalArgumentException::class.java) { draw(broad) }
    }

    private fun shape(vararg commands: JSONObject) = JSONObject().apply {
        put("commands", JSONArray(commands.toList())); put("lineWidth", 4); put("miterLimit", 10)
        put("strokeStart", 0); put("strokeEnd", 1); put("antialiased", false)
        put("strokeColor", rgba(Color.RED)); put("fillColor", rgba(Color.BLUE))
        put("fillRule", "NONZERO"); put("lineCap", "BUTT"); put("lineJoin", "MITER")
    }
    private fun command(op: String, x: Int, y: Int) = JSONObject().put("op", op).put("x", x).put("y", y)

    @Test fun filledShapeAndStrokeHaveDistinctPixels() {
        val path = shape(command("M", 5, 5), command("L", 35, 5), command("L", 35, 25), command("L", 5, 25), JSONObject().put("op", "Z"))
        draw(node(color = Color.BLACK).put("shape", path)).useBitmap {
            assertEquals(Color.BLUE, it.getPixel(20, 15))
            assertEquals(Color.RED, it.getPixel(5, 15))
            assertEquals(Color.BLACK, it.getPixel(1, 15))
        }
    }

    @Test fun strokeTrimmingIsContinuousAcrossContours() {
        val path = shape(command("M", 2, 10), command("L", 22, 10), command("M", 30, 10), command("L", 50, 10))
            .put("fillColor", rgba(Color.TRANSPARENT)).put("strokeStart", 0.25).put("strokeEnd", 0.75)
        draw(node(w = 60, color = Color.BLACK).put("shape", path)).useBitmap {
            assertEquals(Color.BLACK, it.getPixel(5, 10)); assertEquals(Color.RED, it.getPixel(15, 10))
            assertEquals(Color.RED, it.getPixel(35, 10)); assertEquals(Color.BLACK, it.getPixel(45, 10))
        }
    }

    @Test fun unknownPathOperationsAndInvalidStrokeAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { draw(node().put("shape", shape(command("A", 1, 2)))) }
        assertThrows(IllegalArgumentException::class.java) { draw(node().put("shape", shape(command("M", 1, 2)).put("strokeEnd", 2))) }
        assertThrows(IllegalArgumentException::class.java) { draw(node().put("shape", shape(command("M", 1, 2)).put("lineCap", "unknown"))) }
    }

    private fun gradient(kind: String) = JSONObject().put("kind", kind)
        .put("startPoint", JSONObject().put("x", 0).put("y", 0.5))
        .put("endPoint", JSONObject().put("x", 1).put("y", 0.5))
        .put("stops", JSONArray().put(JSONObject().put("location", 0).put("color", rgba(Color.RED)))
            .put(JSONObject().put("location", 1).put("color", rgba(Color.BLUE))))

    @Test fun linearAndRadialGradientsInterpolateAndClamp() {
        for (kind in listOf("LINEAR", "RADIAL")) {
            draw(node().put("gradient", gradient(kind))).useBitmap {
                assertTrue(Color.red(it.getPixel(1, 15)) > 240)
                assertTrue(Color.blue(it.getPixel(38, 15)) > 240)
                assertTrue(Color.red(it.getPixel(20, 15)) in 110..140)
            }
        }
    }

    @Test fun conicalGradientRotatesItsStartAxisAndAppliesOpacity() {
        val value = gradient("CONICAL")
            .put("startPoint", JSONObject().put("x", 0.5).put("y", 0.5))
            .put("endPoint", JSONObject().put("x", 0.5).put("y", 1))
        draw(node().put("opacity", 0.5).put("gradient", value)).useBitmap {
            // Sample just after the sweep seam; pixel centers lie at x + 0.5.
            val below = it.getPixel(19, 25)
            assertTrue(Color.alpha(below) in 127..128)
            assertTrue(Color.red(below) > 240)
            assertTrue(Color.blue(it.getPixel(30, 15)) > Color.blue(below))
        }
    }

    @Test fun invalidGradientStopsAndCoincidentEndpointsAreRefused() {
        val value = gradient("LINEAR")
        value.getJSONArray("stops").getJSONObject(1).put("location", -1)
        assertThrows(IllegalArgumentException::class.java) { draw(node().put("gradient", value)) }
        value.getJSONArray("stops").getJSONObject(1).put("location", 1)
        value.put("endPoint", value.getJSONObject("startPoint"))
        assertThrows(IllegalArgumentException::class.java) { draw(node().put("gradient", value)) }
        assertThrows(IllegalArgumentException::class.java) { draw(node().put("gradient", gradient("unsupported"))) }
    }

    private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T = try { block(this) } finally { recycle() }
}
