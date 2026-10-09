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
 * Association between a role and a configurable permission with Spring Data JPA audit fields.
 * This entity does not provide Envers grant/revoke revision history.
 */
@Entity
@Table(
        name = "gsuif_role_permission",
        indexes = @Index(name = "idx_gsuif_role_permission_permission_id", columnList = "permission_id")
)
public class GsuifRolePermission extends AuditableEntity {

    @EmbeddedId
    private GsuifRolePermissionId id;

    @MapsId("roleId")
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(
            name = "role_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_gsuif_role_permission_role_id")
    )
    private GsuifRole role;

    @MapsId("permissionId")
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(
            name = "permission_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_gsuif_role_permission_permission_id")
    )
    private GsuifPermission permission;

    public GsuifRolePermissionId getId() {
        return id;
    }

    public void setId(GsuifRolePermissionId id) {
        this.id = id;
    }

    public GsuifRole getRole() {
        return role;
    }

    public void setRole(GsuifRole role) {
        this.role = role;
    }

    public GsuifPermission getPermission() {
        return permission;
    }

    public void setPermission(GsuifPermission permission) {
        this.permission = permission;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GsuifRolePermission that)) {
            return false;
        }
        return id != null && id.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
