package io.vdp.protolake.validator;

import io.quarkus.test.junit.QuarkusTest;
import io.vdp.protolake.storage.ValidationException;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import protolake.v1.Bundle;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
public class BundleValidatorDebugTest {
    
    @Inject
    BundleValidator bundleValidator;
    
    @Test
    void debugEmptyBundleNameValidation() {
        // Test the exact case that's failing
        Bundle emptyName = Bundle.newBuilder()
            .setName("lakes/name_lake/bundles/")
            .build();
        
        System.out.println("Testing bundle with name: '" + emptyName.getName() + "'");
        
        try {
            bundleValidator.validate(emptyName);
            System.out.println("No exception thrown!");
        } catch (ValidationException e) {
            System.out.println("Exception message: " + e.getMessage());
            System.out.println("Errors: " + e.getErrors());
            
            // Re-throw for assertion
            throw new RuntimeException(e);
        }
    }
}
