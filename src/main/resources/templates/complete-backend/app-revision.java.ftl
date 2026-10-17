<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.audit;

@jakarta.persistence.Entity
@jakarta.persistence.Table(name = "app_revision")
@org.hibernate.envers.RevisionEntity
public class AppRevision {
    @jakarta.persistence.Id
    @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    @org.hibernate.envers.RevisionNumber
    @jakarta.persistence.Column(name = "rev", nullable = false, updatable = false)
    private Long rev;

    @org.hibernate.envers.RevisionTimestamp
    @jakarta.persistence.Column(name = "revtstmp", nullable = false)
    private long timestamp;

    public Long getRev() { return rev; }
    public long getTimestamp() { return timestamp; }
}
