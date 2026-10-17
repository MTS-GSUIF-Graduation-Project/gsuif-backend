<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.entity;

@jakarta.persistence.MappedSuperclass
@jakarta.persistence.EntityListeners(org.springframework.data.jpa.domain.support.AuditingEntityListener.class)
public abstract class AuditableEntity {
    @org.springframework.data.annotation.CreatedDate
    @jakarta.persistence.Column(name = "created_at", nullable = false, updatable = false)
    private java.time.Instant createdAt;
    @org.springframework.data.annotation.LastModifiedDate
    @jakarta.persistence.Column(name = "updated_at", nullable = false)
    private java.time.Instant updatedAt;
    @org.springframework.data.annotation.CreatedBy
    @jakarta.persistence.Column(name = "created_by", nullable = false, updatable = false, length = 100)
    private String createdBy;
    @org.springframework.data.annotation.LastModifiedBy
    @jakarta.persistence.Column(name = "last_modified_by", nullable = false, length = 100)
    private String lastModifiedBy;

    public java.time.Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.Instant value) { createdAt = value; }
    public java.time.Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.Instant value) { updatedAt = value; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String value) { createdBy = value; }
    public String getLastModifiedBy() { return lastModifiedBy; }
    public void setLastModifiedBy(String value) { lastModifiedBy = value; }
}
