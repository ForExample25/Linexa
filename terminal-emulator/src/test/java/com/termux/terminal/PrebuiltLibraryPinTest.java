package com.termux.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * F-045 / R-11: the prebuilt {@code libtermux.so} files are pinned.
 *
 * Linexa ships these two binaries rather than building them from the vendored
 * {@code src/main/jni/termux.c}. That is acceptable only if a silent change is
 * impossible: this test recomputes each SHA-256 and compares it with
 * {@code src/main/jniLibs/SHA256SUMS}. A rebuilt or replaced binary therefore
 * fails the suite until the manifest and PROVENANCE.md are updated together.
 *
 * The test resolves paths relative to the Gradle module directory (the unit
 * test working directory), like the module's other source pins.
 */
public class PrebuiltLibraryPinTest {

    @Test
    public void everyPackagedBinaryMatchesThePinnedHash() throws Exception {
        File manifest = new File("src/main/jniLibs/SHA256SUMS");
        assertTrue("the pin manifest must exist: " + manifest.getAbsolutePath(), manifest.isFile());

        Map<String, String> expected = parse(manifest);
        assertTrue("the manifest must pin at least one ABI", !expected.isEmpty());

        for (Map.Entry<String, String> entry : expected.entrySet()) {
            File binary = new File("src/main/jniLibs", entry.getKey());
            assertTrue("pinned binary must exist: " + binary, binary.isFile());
            assertEquals(
                "F-045: " + entry.getKey() + " does not match its pinned SHA-256; "
                    + "rebuild with scripts/rebuild-libtermux.sh and update SHA256SUMS + PROVENANCE.md",
                entry.getValue(),
                sha256(binary)
            );
        }
    }

    private static Map<String, String> parse(File manifest) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (String raw : Files.readAllLines(manifest.toPath(), StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length != 2) continue;
            result.put(parts[1], parts[0].toLowerCase());
        }
        return result;
    }

    private static String sha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = Files.readAllBytes(file.toPath());
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
