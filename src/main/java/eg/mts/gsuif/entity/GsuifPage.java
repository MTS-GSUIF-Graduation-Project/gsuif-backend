package eg.mts.gsuif.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * JPA mapping for {@code gsuif_page}.
 *
 * <p>{@code route} is nullable; uniqueness is per project when a route is present.
 */
@Entity
@Table(
        name = "gsuif_page",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_gsuif_page_project_id_name", columnNames = {"project_id", "name"}),
                @UniqueConstraint(name = "uk_gsuif_page_project_id_route", columnNames = {"project_id", "route"}),
                @UniqueConstraint(name = "uk_gsuif_page_project_id_id", columnNames = {"project_id", "id"})
        }
)
public class GsuifPage extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(
            name = "project_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_gsuif_page_project_id")
    )
    private GsuifProject project;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "route", length = 255)
    private String route;

    public UUID getId() {
        return id;
    }

    protected void setId(UUID id) {
        this.id = id;
    }

    public GsuifProject getProject() {
        return project;
    }

    public void setProject(GsuifProject project) {
        this.project = project;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRoute() {
        return route;
    }

    public void setRoute(String route) {
        this.route = route;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GsuifPage that)) {
            return false;
        }
        return id != null && id.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return GsuifPage.class.hashCode();
    }
}
