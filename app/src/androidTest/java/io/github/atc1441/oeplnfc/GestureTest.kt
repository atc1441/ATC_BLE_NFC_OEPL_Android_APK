package io.github.atc1441.oeplnfc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The preview sits inside a ScrollView, which will happily claim any drag once
 * it passes the touch slop - so a pinch or a pan scrolls the page instead of
 * reaching the image. That is a gesture-routing bug: it cannot be caught by a
 * unit test, and `adb shell input` cannot even express a pinch. So it is
 * checked here, with real multi-pointer injection on a real view hierarchy.
 *
 * Routing goes both ways, and which way is right depends on whether there is
 * an image: with one, a drag over the preview places it; with none, the
 * preview is a 260dp hole in the page and the drag has to scroll past it.
 * Both directions are checked below. The tests that need an image ask the
 * activity for a generated one, because the alternative is driving the system
 * photo picker.
 *
 * Run with: ./gradlew :app:connectedDebugAndroidTest   (needs a device/emulator)
 */
@RunWith(AndroidJUnit4::class)
class GestureTest {

    private lateinit var device: UiDevice
    private val pkg = "io.github.atc1441.oeplnfc"

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        launch(withImage = true)
    }

    /**
     * Start the app from scratch, with or without a picture in the preview.
     * [withImage] asks MainActivity for a generated one; see
     * maybeUseGeneratedImage() there for why the picker is not used.
     */
    private fun launch(withImage: Boolean) {
        device.pressHome()

        val ctx = InstrumentationRegistry.getInstrumentation().context
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)!!
            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (withImage) intent.putExtra("oepl_test_image", true)
        ctx.startActivity(intent)
        assertTrue("app did not come up", device.wait(Until.hasObject(By.pkg(pkg)), 10_000))
        device.waitForIdle()
    }

    /**
     * Scroll until [res] is on screen. The swipes go down the far left margin
     * on purpose: the preview now swallows drags over itself, which is the
     * whole point, so scrolling over it would do nothing.
     */
    private fun scrollTo(res: String): UiObject2 {
        repeat(8) {
            val o = device.findObject(By.res(pkg, res))
            if (o != null) return o
            scrollDown()
            device.waitForIdle()
        }
        val o = device.findObject(By.res(pkg, res))
        assertNotNull("$res never became visible", o)
        return o!!
    }

    private fun scrollDown(frac: Int = 4) =
        device.swipe(20, device.displayHeight * 3 / 4,
                     20, device.displayHeight * 3 / 4 - device.displayHeight / frac, 20)

    private fun scrollUp(frac: Int = 4) =
        device.swipe(20, device.displayHeight / 4,
                     20, device.displayHeight / 4 + device.displayHeight / frac, 20)

    /**
     * Put the preview AND the zoom readout on screen together. Without this the
     * label scrolls off while the preview is in view, findObject returns null,
     * and an assertion about "the zoom" quietly compares against an empty
     * string instead of failing.
     */
    private fun showPreviewAndZoom(): UiObject2 {
        repeat(6) { scrollUp(3); device.waitForIdle() }   // back to the top
        repeat(14) {
            val p = device.findObject(By.res(pkg, "preview"))
            val z = device.findObject(By.res(pkg, "lblZoom"))
            if (p != null && z != null && z.text.startsWith("Zoom")) return p
            scrollDown(12)
            device.waitForIdle()
        }
        fail("could not get the preview and the zoom label on screen together")
        error("unreachable")
    }

    private fun zoomLabel(): String {
        val o = device.findObject(By.res(pkg, "lblZoom"))
        assertNotNull("the zoom label is not on screen, so it cannot be checked", o)
        return o!!.text
    }

    @Test
    fun pinchZoomsTheImageAndDoesNotScrollThePage() {
        val preview = showPreviewAndZoom()
        val boundsBefore = preview.visibleBounds
        val zoomBefore = zoomLabel()
        assertTrue("zoom label missing", zoomBefore.startsWith("Zoom"))

        preview.pinchOpen(0.6f)
        device.waitForIdle()

        val after = device.findObject(By.res(pkg, "preview"))
        assertNotNull("preview vanished during the pinch", after)
        val boundsAfter = after!!.visibleBounds

        assertNotEquals(
            "the pinch never reached the preview - the ScrollView ate it",
            zoomBefore, zoomLabel()
        )
        // Allow a pixel of rounding, but nothing that counts as a scroll.
        assertTrue(
            "the page scrolled during the pinch: ${boundsBefore.centerY()} " +
                "-> ${boundsAfter.centerY()}",
            Math.abs(boundsAfter.centerY() - boundsBefore.centerY()) <= 2
        )
    }

    @Test
    fun pinchOpenZoomsInAndPinchCloseZoomsOut() {
        val preview = showPreviewAndZoom()

        fun percent(): Int =
            Regex("(\\d+)").find(zoomLabel())?.groupValues?.get(1)?.toInt() ?: -1

        val start = percent()
        assertTrue("could not read the zoom percentage from '${zoomLabel()}'", start > 0)

        preview.pinchOpen(0.6f)
        device.waitForIdle()
        val opened = percent()
        assertTrue("pinch open did not zoom in: $start -> $opened", opened > start)

        device.findObject(By.res(pkg, "preview")).pinchClose(0.6f)
        device.waitForIdle()
        val closed = percent()
        assertTrue("pinch close did not zoom out: $opened -> $closed", closed < opened)
    }

    @Test
    fun draggingThePreviewPansInsteadOfScrollingThePage() {
        val preview = scrollTo("preview")
        device.waitForIdle()

        val before = preview.visibleBounds
        // A drag well inside the preview, long enough to pass the ScrollView's
        // touch slop several times over.
        device.swipe(
            before.centerX(), before.centerY() + before.height() / 4,
            before.centerX(), before.centerY() - before.height() / 4,
            25
        )
        device.waitForIdle()

        val after = device.findObject(By.res(pkg, "preview"))
        assertNotNull("preview vanished during the drag", after)
        assertEquals(
            "dragging over the preview scrolled the page instead of panning",
            before.centerY(), after!!.visibleBounds.centerY()
        )
    }

    @Test
    fun anEmptyPreviewLetsThePageScrollInstead() {
        launch(withImage = false)
        val preview = scrollTo("preview")
        device.waitForIdle()

        val before = preview.visibleBounds
        // Not zoomLabel(): after the page has scrolled the label may well be
        // off screen, and a test that then fails would be failing about its
        // own bookkeeping rather than about the gesture.
        val zoomBefore = device.findObject(By.res(pkg, "lblZoom"))?.text
        // The same drag that pans a loaded preview. With nothing in it, this
        // has to reach the ScrollView: an area that shows "No image" and still
        // swallows drags makes the page impossible to get past.
        device.swipe(
            before.centerX(), before.centerY() + before.height() / 4,
            before.centerX(), before.centerY() - before.height() / 4,
            25
        )
        device.waitForIdle()

        val after = device.findObject(By.res(pkg, "preview"))
        if (after != null) {
            assertTrue(
                "dragging the empty preview did not scroll the page: " +
                    "${before.centerY()} -> ${after.visibleBounds.centerY()}",
                before.centerY() - after.visibleBounds.centerY() > 20
            )
        }   // gone from the screen altogether is the page having scrolled too

        // And it must not have been taken as a gesture on the way.
        val zoomAfter = device.findObject(By.res(pkg, "lblZoom"))?.text
        if (zoomBefore != null && zoomAfter != null) {
            assertEquals("the empty preview took the drag as a gesture",
                         zoomBefore, zoomAfter)
        }
    }

    @Test
    fun theResetButtonPutsTheZoomBack() {
        val preview = showPreviewAndZoom()
        assertEquals("should start at 100%", "Zoom  100%", zoomLabel())
        preview.pinchOpen(0.6f)
        device.waitForIdle()
        assertNotEquals("the pinch did not change the zoom", "Zoom  100%", zoomLabel())

        scrollTo("btnReset").click()
        device.waitForIdle()
        assertEquals("reset did not restore the zoom", "Zoom  100%", zoomLabel())
    }
}
