package eg.mts.gsuif.exception;

/**
 * Thrown when an attempt is made to create or update an entity with a duplicate business key.
 */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
