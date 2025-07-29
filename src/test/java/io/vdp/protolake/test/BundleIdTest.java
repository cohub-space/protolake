package io.vdp.protolake.test;

import io.vdp.protolake.util.BundleUtil;

public class BundleIdTest {
    public static void main(String[] args) {
        String resourceName = "lakes/name_lake/bundles/";
        String bundleId = BundleUtil.extractBundleId(resourceName);
        
        System.out.println("Resource name: " + resourceName);
        System.out.println("Bundle ID: '" + bundleId + "'");
        System.out.println("Bundle ID length: " + bundleId.length());
        System.out.println("Bundle ID is empty: " + bundleId.isEmpty());
        System.out.println("Bundle ID is null: " + (bundleId == null));
        
        // Test the validation condition
        if (bundleId == null || bundleId.isEmpty()) {
            System.out.println("Result: Bundle name is required");
        } else if (!bundleId.matches("^[a-zA-Z0-9_]+$")) {
            System.out.println("Result: Bundle name must contain only alphanumeric characters and underscores");
        } else {
            System.out.println("Result: Valid bundle name");
        }
    }
}
