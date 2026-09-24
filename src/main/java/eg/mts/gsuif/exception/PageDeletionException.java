package eg.mts.gsuif.exception;

/**
 * Thrown when a page cannot be deleted because dependent metadata versions exist.
 */
public class PageDeletionException extends RuntimeException {

    public PageDeletionException(String message) {
        super(message);
    }
}
