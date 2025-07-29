package io.vdp.protolake.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collection of validation errors.
 * Used internally by the validation pipeline.
 */
public class ValidationErrors {
    
    private final List<ValidationError> errors;
    
    private ValidationErrors(Builder builder) {
        this.errors = Collections.unmodifiableList(new ArrayList<>(builder.errors));
    }
    
    public List<ValidationError> getErrors() {
        return errors;
    }
    
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
    
    public int getErrorCount() {
        return errors.size();
    }
    
    public static Builder newBuilder() {
        return new Builder();
    }
    
    public static class Builder {
        private final List<ValidationError> errors = new ArrayList<>();
        
        public Builder addError(ValidationError error) {
            this.errors.add(error);
            return this;
        }
        
        public Builder addAllErrors(List<ValidationError> errors) {
            this.errors.addAll(errors);
            return this;
        }
        
        public ValidationErrors build() {
            return new ValidationErrors(this);
        }
    }
}
