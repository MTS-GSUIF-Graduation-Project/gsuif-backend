package eg.mts.gsuif.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key for {@link GsuifRolePermission}.
 */
@Embeddable
public class GsuifRolePermissionId implements Serializable {

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "role_id", nullable = false, length = 36)
    private UUID roleId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "permission_id", nullable = false, length = 36)
    private UUID permissionId;

    public GsuifRolePermissionId() {
    }

    public GsuifRolePermissionId(UUID roleId, UUID permissionId) {
        this.roleId = roleId;
        this.permissionId = permissionId;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public void setRoleId(UUID roleId) {
        this.roleId = roleId;
    }

    public UUID getPermissionId() {
        return permissionId;
    }

    public void setPermissionId(UUID permissionId) {
        this.permissionId = permissionId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GsuifRolePermissionId that)) {
            return false;
        }
        return Objects.equals(roleId, that.roleId) && Objects.equals(permissionId, that.permissionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roleId, permissionId);
    }
}
