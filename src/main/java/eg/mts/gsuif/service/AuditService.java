package eg.mts.gsuif.service;

import java.util.List;

/**
 * Service interface for querying Hibernate Envers audit revision history.
 */
public interface AuditService {

    /**
     * Retrieves all revision numbers for a given entity class and identifier.
     *
     * @param entityClass the audited entity class
     * @param id          the entity primary key
     * @return list of revision numbers
     */
    List<Number> getRevisions(Class<?> entityClass, Object id);

    /**
     * Retrieves the state of an audited entity at a specific revision number.
     *
     * @param entityClass the audited entity class
     * @param id          the entity primary key
     * @param revision    the revision number
     * @param <T>         the entity type
     * @return the entity instance as it existed at the specified revision
     */
    <T> T getEntityAtRevision(Class<T> entityClass, Object id, Number revision);
}
