package eg.mts.gsuif.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/**
 * Explicit mapping for {@code gsuif_user_role}: composite PK plus audit columns.
 */
@Entity
@Table(
        name = "gsuif_user_role",
        indexes = @Index(name = "idx_gsuif_user_role_role_id", columnList = "role_id")
)
public class GsuifUserRole extends AuditableEntity {

    @EmbeddedId
    private GsuifUserRoleId id;

    @MapsId("userId")
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_gsuif_user_role_user_id")
    )
    private GsuifUser user;

    @MapsId("roleId")
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(
            name = "role_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_gsuif_user_role_role_id")
    )
    private GsuifRole role;

    public GsuifUserRoleId getId() {
        return id;
    }

    public void setId(GsuifUserRoleId id) {
        this.id = id;
    }

    public GsuifUser getUser() {
        return user;
    }

    public void setUser(GsuifUser user) {
        this.user = user;
    }

    public GsuifRole getRole() {
        return role;
    }

    public void setRole(GsuifRole role) {
        this.role = role;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GsuifUserRole that)) {
            return false;
        }
        return id != null && id.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
