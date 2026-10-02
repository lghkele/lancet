package com.sample.playground;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;

/** Verifies that the AGP 8.6 ScopedArtifacts adapter weaves the Android variant classes. */
public class LancetAgp86IntegrationTest {

    @Test
    public void appliesLancetToAndroidVariant() throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8.name()));
            Main.main(new String[0]);
        } finally {
            System.setOut(original);
        }

        String text = output.toString(StandardCharsets.UTF_8.name());
        assertTrue("Lancet hook was not applied. Output: " + text, text.contains("SetFieldTest"));
    }
}
