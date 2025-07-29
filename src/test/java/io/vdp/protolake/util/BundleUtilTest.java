package io.vdp.protolake.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class BundleUtilTest {
    
    @Test
    void testExtractBundleIdWithEmptyBundleName() {
        // Test the exact case from the failing test
        String resourceName = "lakes/name_lake/bundles/";
        String bundleId = BundleUtil.extractBundleId(resourceName);
        
        System.out.println("Resource name: '" + resourceName + "'");
        System.out.println("Bundle ID: '" + bundleId + "'");
        System.out.println("Bundle ID length: " + bundleId.length());
        System.out.println("Bundle ID isEmpty: " + bundleId.isEmpty());
        
        assertThat(bundleId).isEmpty();
        
        // Test validation logic
        if (bundleId == null || bundleId.isEmpty()) {
            System.out.println("Should error with: Bundle name is required");
        } else if (!bundleId.matches("^[a-zA-Z0-9_]+$")) {
            System.out.println("Should error with: Bundle name must contain only alphanumeric characters and underscores");
        }
    }
    
    @Test
    void testExtractBundleIdWithValidBundleName() {
        String resourceName = "lakes/test_lake/bundles/my_bundle";
        String bundleId = BundleUtil.extractBundleId(resourceName);
        
        assertThat(bundleId).isEqualTo("my_bundle");
    }
    
    @Test
    void testExtractBundleIdWithInvalidCharacters() {
        String resourceName = "lakes/test_lake/bundles/my-bundle!";
        String bundleId = BundleUtil.extractBundleId(resourceName);
        
        assertThat(bundleId).isEqualTo("my-bundle!");
        assertThat(bundleId.matches("^[a-zA-Z0-9_]+$")).isFalse();
    }
}
