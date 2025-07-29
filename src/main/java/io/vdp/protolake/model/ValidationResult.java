package io.vdp.protolake.model;

import protolake.v1.BuildOperationMetadata;

/**
 * Result of a validation run.
 * Used internally by the validation pipeline.
 */
public class ValidationResult {
    
    private final boolean success;
    private final ValidationErrors errors;
    private final BuildOperationMetadata metadata;
    
    private ValidationResult(Builder builder) {
        this.success = builder.success;
        this.errors = builder.errors != null ? builder.errors : ValidationErrors.newBuilder().build();
        this.metadata = builder.metadata;
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public ValidationErrors getErrors() {
        return errors;
    }
    
    public BuildOperationMetadata getMetadata() {
        return metadata;
    }
    
    public static Builder newBuilder() {
        return new Builder();
    }
    
    public static class Builder {
        private boolean success;
        private ValidationErrors errors;
        private BuildOperationMetadata metadata;
        
        public Builder setSuccess(boolean success) {
            this.success = success;
            return this;
        }
        
        public Builder setErrors(ValidationErrors errors) {
            this.errors = errors;
            return this;
        }
        
        public Builder setMetadata(BuildOperationMetadata metadata) {
            this.metadata = metadata;
            return this;
        }
        
        public ValidationResult build() {
            return new ValidationResult(this);
        }
    }
}
