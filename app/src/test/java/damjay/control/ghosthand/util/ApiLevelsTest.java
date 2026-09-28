package damjay.control.ghosthand.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The version policy, tested at every boundary.
 *
 * <p>This is the one piece of the API-19 work that a plain JVM can prove: {@link
 * ApiLevels} takes the SDK level as an argument, so the questions "can a KitKat phone
 * mirror its screen", "can a Lollipop phone be touched" and "can an Oreo phone do
 * both" are just function calls. The calls themselves
 * ({@code dispatchGesture}, {@code MediaProjection}) are covered by
 * {@code check_api.py}, which reads the compiled bytecode and the reference platform jar.
 *
 * <p>The numbers are the interesting part, and they are the reason an old phone is still
 * useful here: 19 and 20 are guest-only, 21-23 mirror without being controllable, and 24
 * is the first that does everything.
 */
public class ApiLevelsTest {

    @Test
    public void kitkatCanOnlyBeTheGuest() {
        assertTrue(ApiLevels.canGuest(19));
        assertFalse("no MediaProjection before 21", ApiLevels.canHost(19));
        assertFalse("no dispatchGesture before 24", ApiLevels.canInject(19));
    }

    @Test
    public void gingerbreadIsNotSupportedAtAll() {
        // minSdkVersion is 19, so this should never be reached - but the predicate has
        // to agree with the manifest, or a future edit could make them disagree.
        assertFalse(ApiLevels.canGuest(9));
        assertFalse(ApiLevels.canHost(9));
        assertFalse(ApiLevels.canInject(9));
    }

    @Test
    public void lollipopMirrorsButCannotBeControlled() {
        assertTrue(ApiLevels.canHost(21));
        assertFalse(ApiLevels.canInject(21));
        assertTrue(ApiLevels.canHost(22));
        assertFalse(ApiLevels.canInject(22));
        assertTrue(ApiLevels.canHost(23));
        assertFalse("marshmallow is the last without gesture injection",
                ApiLevels.canInject(23));
    }

    @Test
    public void nougatIsTheFirstFullHost() {
        assertTrue(ApiLevels.canInject(24));
        assertTrue(ApiLevels.canHost(24));
    }

    @Test
    public void modernVersionsCanDoEverything() {
        for (int sdk = 24; sdk <= 35; sdk++) {
            assertTrue("API " + sdk + " should host", ApiLevels.canHost(sdk));
            assertTrue("API " + sdk + " should inject", ApiLevels.canInject(sdk));
            assertTrue("API " + sdk + " should guest", ApiLevels.canGuest(sdk));
        }
    }

    @Test
    public void constantsMatchThePlatformNumbers() {
        // Spelled out rather than taken from Build.VERSION_CODES: this class must stay
        // pure Java so the test above can run without android.jar on the classpath.
        assertTrue(ApiLevels.MIN_SUPPORTED_API == 19);   // Android 4.4 KitKat
        assertTrue(ApiLevels.MIN_HOST_API == 21);        // Android 5.0 Lollipop
        assertTrue(ApiLevels.MIN_TOUCH_API == 24);       // Android 7.0 Nougat
        assertTrue(ApiLevels.MIN_SUPPORTED_API < ApiLevels.MIN_HOST_API);
        assertTrue(ApiLevels.MIN_HOST_API < ApiLevels.MIN_TOUCH_API);
    }
}
