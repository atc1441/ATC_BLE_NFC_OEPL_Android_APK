package io.github.atc1441.oeplnfc

import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The command screen builds its form at runtime from [Commands], for more than
 * fifty entries. A single bad spinner default or an unparsable value shows up
 * only when that one command is picked - which is exactly the kind of thing
 * nobody finds by hand. So every entry gets selected here once.
 */
@RunWith(AndroidJUnit4::class)
class CommandScreenTest {

    private fun walk(groupIndex: Int, expected: List<CommandSpec>) {
        ActivityScenario.launch(CommandActivity::class.java).use { scenario ->
            scenario.onActivity { act ->
                val spinners = ArrayList<Spinner>()
                collect(act.window.decorView as? android.view.ViewGroup, spinners)
                assertTrue("no spinners on the screen", spinners.size >= 2)
                val group = spinners[0]
                val command = spinners[1]

                group.setSelection(groupIndex)
                // setSelection posts; apply the selection listener directly so
                // the walk is deterministic rather than racing the message queue.
                group.onItemSelectedListener?.onItemSelected(group, null, groupIndex, 0)

                assertEquals(
                    "the command list does not match the catalogue",
                    expected.size, command.adapter.count
                )

                for (i in expected.indices) {
                    command.onItemSelectedListener?.onItemSelected(command, null, i, 0)
                    val spec = expected[i]

                    // The help text must actually say something.
                    val help = findText(act.window.decorView as android.view.ViewGroup)
                        .firstOrNull { it.text.toString().startsWith(spec.help.take(20)) }
                    assertTrue(
                        "'${spec.name}' shows no help",
                        spec.help.length < 20 || help != null
                    )

                    // And the payload must build from whatever the form now
                    // holds, unless the command deliberately wants input.
                    if (spec.params.none { it.required }) {
                        Commands.encode(spec.params, spec.params.map { it.default })
                    }
                }
            }
        }
    }

    private fun collect(v: android.view.ViewGroup?, into: MutableList<Spinner>) {
        if (v == null) return
        for (i in 0 until v.childCount) {
            when (val c = v.getChildAt(i)) {
                is Spinner -> into.add(c)
                is LinearLayout -> collect(c, into)
                is android.view.ViewGroup -> collect(c, into)
            }
        }
    }

    private fun findText(v: android.view.ViewGroup): List<TextView> {
        val out = ArrayList<TextView>()
        fun rec(g: android.view.ViewGroup) {
            for (i in 0 until g.childCount) {
                when (val c = g.getChildAt(i)) {
                    is TextView -> out.add(c)
                    is android.view.ViewGroup -> rec(c)
                }
            }
        }
        rec(v)
        return out
    }

    /** Every view, anywhere under [v], flat. */
    private fun findViews(v: android.view.ViewGroup, into: MutableList<View> = ArrayList()):
        MutableList<View> {
        for (i in 0 until v.childCount) {
            val c = v.getChildAt(i)
            into.add(c)
            if (c is android.view.ViewGroup) findViews(c, into)
        }
        return into
    }

    private fun seekBars(v: android.view.ViewGroup) = findViews(v).filterIsInstance<SeekBar>()

    private fun checkBoxes(v: android.view.ViewGroup) = findViews(v).filterIsInstance<CheckBox>()

    /** Select one command by name and hand back the decor view. */
    private fun pick(name: String, body: (android.view.ViewGroup) -> Unit) {
        val group = if (Commands.tag.any { it.name == name }) 0 else 1
        val list = if (group == 0) Commands.tag else Commands.debug
        val at = list.indexOfFirst { it.name == name }
        assertTrue("no command called '$name'", at >= 0)
        ActivityScenario.launch(CommandActivity::class.java).use { scenario ->
            scenario.onActivity { act ->
                val spinners = ArrayList<Spinner>()
                collect(act.window.decorView as? android.view.ViewGroup, spinners)
                spinners[0].onItemSelectedListener
                    ?.onItemSelected(spinners[0], null, group, 0)
                spinners[1].onItemSelectedListener
                    ?.onItemSelected(spinners[1], null, at, 0)
                body(act.window.decorView as android.view.ViewGroup)
            }
        }
    }

    /**
     * The point of the whole parameter model: the LED pattern has to come up
     * as sliders and swatches, not as twelve boxes holding 0x23. Three groups
     * of (red, green, blue, flashes, spacing) plus brightness and repeats is
     * seventeen sliders, so anything in that region means the editor built.
     */
    @Test
    fun theLedPatternComesUpAsSliders() = pick("LED pattern") { root ->
        val bars = seekBars(root)
        assertTrue("the LED editor built ${bars.size} sliders", bars.size >= 15)
        // And the preview must say what it is about to send.
        val wire = findText(root).map { it.text.toString() }
        assertTrue(
            "nothing shows the bytes being sent",
            wire.any { it.contains("cmd 0x0015") && it.contains("12 bytes") }
        )
    }

    @Test
    fun aRangeParameterComesUpAsASlider() = pick("OEPL advertising period") { root ->
        val bars = seekBars(root)
        assertEquals("one range, one slider", 1, bars.size)
        // 100..10000 inclusive.
        assertEquals(9900, bars[0].max)
        assertTrue(
            "the slider does not say what it means",
            findText(root).any { it.text.contains("ms") && it.text.contains("every") }
        )
    }

    @Test
    fun aGuardByteComesUpAsASwitch() = pick("Flash erase sector") { root ->
        assertTrue(
            "the 0xA5 confirmation is not a switch",
            checkBoxes(root).any { it.text.contains("confirm") }
        )
    }

    @Test
    fun aPinParameterComesUpAsNamedPins() = pick("GPIO set pin") { root ->
        val spinners = ArrayList<Spinner>()
        collect(root, spinners)
        // group, command, then the pin list.
        assertTrue("no pin dropdown", spinners.size >= 3)
        assertEquals(40, spinners[2].adapter.count)
        assertEquals("PA0", spinners[2].adapter.getItem(0))
    }

    @Test
    fun everyTagCommandBuildsItsForm() = walk(0, Commands.tag)

    @Test
    fun everyDebugCommandBuildsItsForm() = walk(1, Commands.debug)

    @Test
    fun theScreenOpensAndOffersBothCatalogues() {
        ActivityScenario.launch(CommandActivity::class.java).use { scenario ->
            scenario.onActivity { act ->
                val spinners = ArrayList<Spinner>()
                collect(act.window.decorView as? android.view.ViewGroup, spinners)
                assertEquals("expected a group and a command spinner", 2, spinners[0].adapter.count)
                assertEquals(Commands.tag.size, spinners[1].adapter.count)
            }
        }
    }
}
