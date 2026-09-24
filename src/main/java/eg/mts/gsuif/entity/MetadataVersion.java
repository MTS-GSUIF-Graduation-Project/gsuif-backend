package eg.mts.gsuif.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * JPA mapping for {@code gsuif_metadata_version}.
 *
 * <p>The composite FK {@code (project_id, page_id)} is mapped through {@link GsuifPage}
 * so a version cannot reference a page from a different project (DEC-024).
 */
@org.hibernate.annotations.Immutable
@Entity
@Table(
        name = "gsuif_metadata_version",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_gsuif_metadata_version_page_id_version",
                columnNames = {"page_id", "version"}
        )
)
@Check(name = "ck_gsuif_metadata_version_version", constraints = "version >= 1")
public class MetadataVersion extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumns(
            value = {
                    @JoinColumn(name = "project_id", referencedColumnName = "project_id", nullable = false),
                    @JoinColumn(name = "page_id", referencedColumnName = "id", nullable = false)
            },
            foreignKey = @ForeignKey(name = "fk_gsuif_metadata_version_project_id_page_id")
    )
    private GsuifPage page;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "schema_version", nullable = false, length = 20)
    private String schemaVersion;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "snapshot", nullable = false)
    private String snapshot;

    public UUID getId() {
        return id;
    }

    protected void setId(UUID id) {
        this.id = id;
    }

    public GsuifPage getPage() {
        return page;
    }

    public void setPage(GsuifPage page) {
        this.page = page;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(String snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MetadataVersion that)) {
            return false;
        }
        return id != null && id.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return MetadataVersion.class.hashCode();
    }
}
