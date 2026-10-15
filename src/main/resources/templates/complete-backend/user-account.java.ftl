<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.security;

@jakarta.persistence.Entity
@jakarta.persistence.Table(name = "user_accounts", uniqueConstraints =
        @jakarta.persistence.UniqueConstraint(name = "uq_user_accounts_username", columnNames = "username"))
public class UserAccount {
    public enum Role { ROLE_ADMIN, ROLE_USER }

    @jakarta.persistence.Id
    @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.UUID)
    @jakarta.persistence.Column(name = "id", nullable = false, updatable = false)
    private java.util.UUID id;

    @jakarta.persistence.Column(name = "username", nullable = false, length = 100)
    private String username;

    @jakarta.persistence.Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @jakarta.persistence.Column(name = "enabled", nullable = false)
    private boolean enabled;

    @jakarta.persistence.ElementCollection(fetch = jakarta.persistence.FetchType.LAZY)
    @jakarta.persistence.CollectionTable(name = "user_account_roles",
            joinColumns = @jakarta.persistence.JoinColumn(name = "user_id"))
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR)
    @jakarta.persistence.Column(name = "role", nullable = false, length = 32)
    private java.util.Set<Role> roles = new java.util.HashSet<>();

    public java.util.UUID getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public java.util.Set<Role> getRoles() { return roles; }
}
