package io.vdp.protolake.storage;

import java.util.Collections;
import java.util.List;

/**
 * Exception thrown when validation fails for lake or bundle operations.
 */
public class ValidationException extends Exception {
    private final List<String> errors;
    
    public ValidationException(String message, List<String> errors) {
        super(message + ": " + String.join(", ", errors));
        this.errors = Collections.unmodifiableList(errors);
    }
    
    public ValidationException(String message, String error) {
        this(message, Collections.singletonList(error));
    }
    
    /**
     * Gets the list of validation errors.
     * 
     * @return Unmodifiable list of error messages
     */
    public List<String> getErrors() {
        return errors;
    }
}
