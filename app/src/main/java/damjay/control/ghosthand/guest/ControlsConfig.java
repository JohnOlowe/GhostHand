package damjay.control.ghosthand.guest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The pure rules behind the guest's control bar: which of the three states it
 * is in, which buttons each state shows, and whether the bar docks (pushes the
 * video up) or floats over it - decided independently per state.
 *
 * <p>Why a class with no Android imports: these are exactly the decisions that
 * deserve unit tests (see ControlsConfigTest) - state transitions, per-state
 * button selection, CSV round-trips of the settings, and the volume percentage
 * the HUD shows. Everything about pixels stays in GuestActivity.
 *
 * <p>The three states, as the settings page describes them:
 * <pre>
 *   UNEXPANDED  nothing but a small "▲" handle in the corner
 *   ONE_LINE    one row: the chosen one-line buttons between × and ▲
 *   FULL        every chosen expanded button, wrapping into rows, plus the
 *               structural row (▼ collapse, Settings, Full screen)
 * </pre>
 * Expanding walks UNEXPANDED -> ONE_LINE -> FULL; collapsing walks it back.
 * Both "one-line" and "FULL" independently choose FLOAT (overlay the video)
 * or PUSH (dock underneath, shrinking the video area).
 */
public final class ControlsConfig {

    /** The three control-bar states. */
    public enum State { UNEXPANDED, ONE_LINE, FULL }

    /** How a bar meets the video: float over it, or dock and push it up. */
    public enum Dock { FLOAT, PUSH }

    /**
     * Every toggleable button, in canonical display order. The settings page
     * lists these, and both per-state selections are subsets of this list -
     * an id stored by an older build that no longer exists is dropped on read.
     */
    public static final String[] BUTTON_IDS = {
            "back", "home", "recents", "shade", "rotate",
            "clip_to", "clip_from", "compose",
            "vol_up", "vol_down", "media",
    };

    /** First-run one-line row: the four classic navigation buttons. */
    public static final String[] DEFAULT_ONE_LINE = {
            "back", "home", "recents", "shade",
    };

    /** First-run expanded panel: everything the bar can offer. */
    public static final String[] DEFAULT_EXPANDED = {
            "back", "home", "recents", "shade", "rotate",
            "clip_to", "clip_from", "compose",
            "vol_up", "vol_down", "media",
    };

    private ControlsConfig() {
    }

    /** UNEXPANDED -> ONE_LINE -> FULL (FULL stays, there is no further level). */
    public static State expand(State state) {
        switch (state) {
            case UNEXPANDED: return State.ONE_LINE;
            case ONE_LINE: return State.FULL;
            default: return State.FULL;
        }
    }

    /** FULL -> ONE_LINE -> UNEXPANDED (UNEXPANDED stays; the handle is the floor). */
    public static State collapse(State state) {
        switch (state) {
            case FULL: return State.ONE_LINE;
            case ONE_LINE: return State.UNEXPANDED;
            default: return State.UNEXPANDED;
        }
    }

    /**
     * Reads a stored CSV of button ids, keeping only ids that still exist.
     * Unknown ids must not survive: they would silently disable a selection
     * made by a build that had a button this one removed (or renamed).
     */
    public static Set<String> parseIds(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null || csv.isEmpty()) {
            return out;
        }
        for (String raw : csv.split(",")) {
            String id = raw.trim();
            if (isKnown(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** The inverse of {@link #parseIds}: canonical order, comma-separated. */
    public static String toCsv(Set<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : BUTTON_IDS) {
            if (ids.contains(id)) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(id);
            }
        }
        return sb.toString();
    }

    /**
     * Which buttons a state shows, in canonical order. UNEXPANDED shows none
     * (its handle and the other states' structural buttons are not user
     * choices); ONE_LINE reads the one-line selection; FULL the expanded one.
     */
    public static List<String> buttonsFor(State state, Set<String> oneLine, Set<String> expanded) {
        List<String> out = new ArrayList<>();
        if (state == State.UNEXPANDED) {
            return out;
        }
        Set<String> chosen = state == State.ONE_LINE ? oneLine : expanded;
        for (String id : BUTTON_IDS) {
            if (chosen.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** Media volume as a whole percentage for the HUD (0 when max is bogus). */
    public static int volumePct(int volume, int max) {
        if (max <= 0) {
            return 0;
        }
        int pct = Math.round(volume * 100f / max);
        if (pct < 0) {
            return 0;
        }
        return pct > 100 ? 100 : pct;
    }

    private static boolean isKnown(String id) {
        for (String known : BUTTON_IDS) {
            if (known.equals(id)) {
                return true;
            }
        }
        return false;
    }
}
