package io.vdp.protolake.model;

/**
 * Represents a validation error found during proto validation.
 * Used internally by the validation pipeline.
 */
public class ValidationError {
    
    public enum Type {
        LINT,
        BREAKING_CHANGE,
        SYNTAX,
        DEPENDENCY
    }
    
    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }
    
    private String file;
    private int line;
    private int column;
    private String message;
    private Type type;
    private Severity severity;
    
    private ValidationError(Builder builder) {
        this.file = builder.file;
        this.line = builder.line;
        this.column = builder.column;
        this.message = builder.message;
        this.type = builder.type;
        this.severity = builder.severity;
    }
    
    public String getFile() {
        return file;
    }
    
    public int getLine() {
        return line;
    }
    
    public int getColumn() {
        return column;
    }
    
    public String getMessage() {
        return message;
    }
    
    public Type getType() {
        return type;
    }
    
    public Severity getSeverity() {
        return severity;
    }
    
    public static Builder newBuilder() {
        return new Builder();
    }
    
    public static class Builder {
        private String file;
        private int line;
        private int column;
        private String message;
        private Type type = Type.LINT;
        private Severity severity = Severity.WARNING;
        
        public Builder setFile(String file) {
            this.file = file;
            return this;
        }
        
        public Builder setLine(int line) {
            this.line = line;
            return this;
        }
        
        public Builder setColumn(int column) {
            this.column = column;
            return this;
        }
        
        public Builder setMessage(String message) {
            this.message = message;
            return this;
        }
        
        public Builder setType(Type type) {
            this.type = type;
            return this;
        }
        
        public Builder setSeverity(Severity severity) {
            this.severity = severity;
            return this;
        }
        
        public ValidationError build() {
            return new ValidationError(this);
        }
    }
}
