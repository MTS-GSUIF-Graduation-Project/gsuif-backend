package eg.mts.gsuif.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * JPA mapping for {@code gsuif_project}.
 */
@Entity
@Table(
        name = "gsuif_project",
        uniqueConstraints = @UniqueConstraint(name = "uk_gsuif_project_name", columnNames = "name")
)
public class GsuifProject extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private UUID id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "description")
    private String description;

    public UUID getId() {
        return id;
    }

    protected void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GsuifProject that)) {
            return false;
        }
        return id != null && id.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return GsuifProject.class.hashCode();
    }
}
