package eg.mts.gsuif.service.impl;

import eg.mts.gsuif.service.AuditService;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Implementation of {@link AuditService} utilizing Hibernate Envers {@link AuditReader}.
 */
@Service
public class AuditServiceImpl implements AuditService {

    private final EntityManager entityManager;

    public AuditServiceImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Number> getRevisions(Class<?> entityClass, Object id) {
        AuditReader auditReader = AuditReaderFactory.get(entityManager);
        return auditReader.getRevisions(entityClass, id);
    }

    @Override
    @Transactional(readOnly = true)
    public <T> T getEntityAtRevision(Class<T> entityClass, Object id, Number revision) {
        AuditReader auditReader = AuditReaderFactory.get(entityManager);
        return auditReader.find(entityClass, id, revision);
    }
}
