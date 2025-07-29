package io.vdp.protolake.storage;

import io.vdp.protolake.util.BundleUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class BundleNameDebugTest {
    
    @Test
    void debugBundleNameExtraction() {
        // Test the exact input from the failing test
        testExtraction("lakes/name_lake/bundles/", "");
        
        // Test other edge cases
        testExtraction("lakes/name_lake/bundles", "");
        testExtraction("lakes/name_lake/bundles/valid_name", "valid_name");
        testExtraction("invalid_format", "");
        testExtraction(null, "");
        testExtraction("", "");
        testExtraction("lakes//bundles/", "");
        testExtraction("lakes/lake/bundles/bundle/extra", "bundle/extra");
    }
    
    private void testExtraction(String input, String expected) {
        String extracted = BundleUtil.extractBundleId(input);
        
        System.out.println("\nInput: '" + input + "'");
        System.out.println("Extracted: '" + extracted + "'");
        System.out.println("Expected: '" + expected + "'");
        System.out.println("Is empty: " + extracted.isEmpty());
        System.out.println("Length: " + extracted.length());
        System.out.println("Matches expected: " + extracted.equals(expected));
        
        // Test regex for non-empty values
        if (!extracted.isEmpty()) {
            boolean matchesRegex = extracted.matches("^[a-zA-Z0-9_]+$");
            System.out.println("Matches regex: " + matchesRegex);
        }
        
        // Assertion
        assertThat(extracted).isEqualTo(expected);
    }
}
