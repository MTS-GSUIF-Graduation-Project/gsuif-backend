<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.exception;

/** Service implementations throw this only when the requested CRUD record is absent. */
public final class RequestedRecordNotFoundException extends RuntimeException {
    public RequestedRecordNotFoundException() {
        super("Requested record not found");
    }
}
