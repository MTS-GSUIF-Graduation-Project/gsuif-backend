package eg.mts.gsuif.exception;

/**
 * Thrown when an assigned permission cannot be deleted.
 */
public class PermissionDeletionException extends RuntimeException {

    public PermissionDeletionException(String message) {
        super(message);
    }
}
