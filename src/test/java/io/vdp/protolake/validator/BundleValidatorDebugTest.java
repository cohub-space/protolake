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
        Bundle emptyName = Bundle.newBuilder()
            .setName("lakes/name_lake/bundles/")
            .build();

        assertThatThrownBy(() -> bundleValidator.validate(emptyName))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Bundle name is required");
    }
}
