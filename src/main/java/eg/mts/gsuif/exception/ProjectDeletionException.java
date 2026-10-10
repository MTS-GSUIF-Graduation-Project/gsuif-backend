package eg.mts.gsuif.exception;

/**
 * Thrown when a project cannot be deleted because dependent domain entities (e.g. pages) exist.
 */
public class ProjectDeletionException extends RuntimeException {

    public ProjectDeletionException(String message) {
        super(message);
    }
}
