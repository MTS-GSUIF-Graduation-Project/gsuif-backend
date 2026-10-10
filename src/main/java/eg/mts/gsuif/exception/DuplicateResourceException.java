package eg.mts.gsuif.exception;

/**
 * Thrown when an attempt is made to create or update an entity with a duplicate business key.
 */
public class DuplicateResourceException extends RuntimeException {

    private final String fieldName;

    public DuplicateResourceException(String message) {
        super(message);
        this.fieldName = null;
    }

    public DuplicateResourceException(String fieldName, String message) {
        super(message);
        this.fieldName = fieldName;
    }

    public String getFieldName() {
        return fieldName;
    }
}
