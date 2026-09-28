package damjay.control.ghosthand.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Pins the Shizuku client identity in AndroidManifest.xml.
 *
 * <p>This is not bureaucracy: the Shizuku server's {@code BinderSender}
 * iterates installed packages and pushes the binder ONLY to packages that
 * request {@code moe.shizuku.manager.permission.API_V23} - and that push is
 * the single way a client ever receives a binder. The official provider AAR
 * declares it in its own manifest (Gradle merges it in); this toolchain
 * vendors the jar (classes only), so a missing line here means Shizuku is
 * silently, permanently dead: {@code pingBinder()} false forever, the status
 * row stuck on "not running", and restarting Shizuku changes nothing. That is
 * exactly the bug this test exists to never let back.
 */
public class ShizukuWiringTest {

    private static final String API_V23 = "moe.shizuku.manager.permission.API_V23";

    private static Document manifest() {
        File f = locate("app/src/main/AndroidManifest.xml");
        assertNotNull("AndroidManifest.xml not found from " + new File("").getAbsoluteFile(), f);
        try {
            // Not namespace-aware on purpose: attributes keep their prefixes
            // ("android:name"), which is what the manifest actually writes.
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f);
        } catch (Exception e) {
            throw new AssertionError("manifest did not parse: " + e, e);
        }
    }

    /** Walks up from the working directory so the test is independent of CWD. */
    private static File locate(String rel) {
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++) {
            File candidate = new File(dir, rel);
            if (candidate.isFile()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    private static Element sole(String tag) {
        NodeList list = manifest().getElementsByTagName(tag);
        assertEquals("expected exactly one <" + tag + ">", 1, list.getLength());
        return (Element) list.item(0);
    }

    @Test
    public void requestsThePermissionTheServerLooksForBeforeSendingTheBinder() {
        NodeList all = manifest().getElementsByTagName("uses-permission");
        for (int i = 0; i < all.getLength(); i++) {
            if (API_V23.equals(((Element) all.item(i)).getAttribute("android:name"))) {
                return; // found
            }
        }
        throw new AssertionError(
                "uses-permission " + API_V23 + " missing - the Shizuku server skips "
                        + "packages without it and the binder is never delivered");
    }

    @Test
    public void declaresTheV3ClientFlagTheManagerFiltersOn() {
        // Without this, the app never appears in Shizuku's authorization lists
        // (ShizukuService.getApplications / AuthorizationManager both check it).
        NodeList all = manifest().getElementsByTagName("meta-data");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if ("moe.shizuku.client.V3_SUPPORT".equals(e.getAttribute("android:name"))) {
                assertEquals("true", e.getAttribute("android:value"));
                return;
            }
        }
        throw new AssertionError("meta-data moe.shizuku.client.V3_SUPPORT missing");
    }

    @Test
    public void providerEntryMatchesTheOfficialSnippet() {
        Element p = sole("provider");
        // aapt2 does not substitute ${applicationId}, so this is written out.
        assertEquals("rikka.shizuku.ShizukuProvider", p.getAttribute("android:name"));
        assertEquals("damjay.control.ghosthand.shizuku", p.getAttribute("android:authorities"));
        assertEquals("true", p.getAttribute("android:exported"));
        assertEquals("false", p.getAttribute("android:multiprocess"));
        assertEquals("true", p.getAttribute("android:enabled"));
        assertTrue("guarded against normal apps, per the official snippet",
                p.getAttribute("android:permission").endsWith("INTERACT_ACROSS_USERS_FULL"));
    }
}
