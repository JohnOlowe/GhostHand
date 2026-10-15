package damjay.control.ghosthand.guest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import damjay.control.ghosthand.guest.ControlsConfig.Dock;
import damjay.control.ghosthand.guest.ControlsConfig.State;

/**
 * The control-bar rules: the expand/collapse walk, per-state button selection,
 * settings CSV round-trips (including unknown-id hygiene), first-run defaults
 * and the volume percentage the HUD prints.
 */
public class ControlsConfigTest {

    // ------------------------- state machine -------------------------------

    @Test
    public void expandWalksUpAndCollapseWalksDown() {
        assertEquals(State.ONE_LINE, ControlsConfig.expand(State.UNEXPANDED));
        assertEquals(State.FULL, ControlsConfig.expand(State.ONE_LINE));
        // No fourth level: expanding again must not wrap or explode.
        assertEquals(State.FULL, ControlsConfig.expand(State.FULL));

        assertEquals(State.ONE_LINE, ControlsConfig.collapse(State.FULL));
        assertEquals(State.UNEXPANDED, ControlsConfig.collapse(State.ONE_LINE));
        // The handle is the floor: collapsing again stays collapsed.
        assertEquals(State.UNEXPANDED, ControlsConfig.collapse(State.UNEXPANDED));
    }

    // --------------------------- selections --------------------------------

    @Test
    public void oneLineShowsItsOwnSelectionInCanonicalOrder() {
        Set<String> oneLine = ControlsConfig.parseIds("shade,back,vol_up");
        Set<String> expanded = ControlsConfig.parseIds("home,recents");
        List<String> got = ControlsConfig.buttonsFor(State.ONE_LINE, oneLine, expanded);
        // Canonical order (back before shade before vol_up), not CSV order.
        assertEquals(Arrays.asList("back", "shade", "vol_up"), got);
    }

    @Test
    public void expandedShowsItsOwnSelectionAndUnexpandedShowsNothing() {
        Set<String> oneLine = ControlsConfig.parseIds("back");
        Set<String> expanded = ControlsConfig.parseIds("media,vol_down");
        assertEquals(Arrays.asList("vol_down", "media"),
                ControlsConfig.buttonsFor(State.FULL, oneLine, expanded));
        assertTrue(ControlsConfig.buttonsFor(State.UNEXPANDED, oneLine, expanded).isEmpty());
    }

    // --------------------------- CSV hygiene -------------------------------

    @Test
    public void csvRoundTripDropsUnknownIdsAndDeduplicates() {
        Set<String> ids = ControlsConfig.parseIds("back, bogon ,media, back,");
        assertEquals(new LinkedHashSet<>(Arrays.asList("back", "media")), ids);
        // Written back in canonical order, so stored prefs are stable.
        assertEquals("back,media", ControlsConfig.toCsv(ids));
        // And parsing what we wrote returns exactly the same set.
        assertEquals(ids, ControlsConfig.parseIds(ControlsConfig.toCsv(ids)));
    }

    @Test
    public void defaultsAreValidSubsetsAndUnique() {
        // First run: the one-line row must be a subset of the expanded panel,
        // otherwise expanding would *remove* buttons the user never touched.
        Set<String> oneLine = ControlsConfig.parseIds(String.join(",", ControlsConfig.DEFAULT_ONE_LINE));
        Set<String> expanded = ControlsConfig.parseIds(String.join(",", ControlsConfig.DEFAULT_EXPANDED));
        assertTrue(expanded.containsAll(oneLine));
        assertEquals(ControlsConfig.BUTTON_IDS.length, expanded.size());
        for (String id : ControlsConfig.DEFAULT_ONE_LINE) {
            assertTrue("default one-line id must be known: " + id,
                    expanded.contains(id));
        }
        // No duplicate ids in the registry (duplicates would render twice).
        Set<String> unique = new LinkedHashSet<>(Arrays.asList(ControlsConfig.BUTTON_IDS));
        assertEquals(ControlsConfig.BUTTON_IDS.length, unique.size());
        assertFalse(ControlsConfig.BUTTON_IDS.length == 0);
    }

    // ----------------------------- volume ----------------------------------

    @Test
    public void volumePercentagesAreClampedAndRounded() {
        assertEquals(0, ControlsConfig.volumePct(0, 15));
        assertEquals(100, ControlsConfig.volumePct(15, 15));
        assertEquals(50, ControlsConfig.volumePct(5, 10));
        // 7 of 15 = 46.7% -> rounds to 47, never 46 (truncation would drift).
        assertEquals(47, ControlsConfig.volumePct(7, 15));
        // A broken max must not divide by zero or invent a level.
        assertEquals(0, ControlsConfig.volumePct(3, 0));
        assertEquals(100, ControlsConfig.volumePct(99, 10));
    }
}
