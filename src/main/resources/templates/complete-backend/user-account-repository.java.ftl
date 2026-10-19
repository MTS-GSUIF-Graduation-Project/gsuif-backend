<#ftl strip_whitespace=true encoding="UTF-8">
package ${packageName}.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {
    @EntityGraph(attributePaths = "roles")
    Optional<UserAccount> findByUsername(String username);

    @Override
    @EntityGraph(attributePaths = "roles")
    Optional<UserAccount> findById(UUID id);

    @org.springframework.data.jpa.repository.Query("select count(u) from UserAccount u join u.roles role where role = :role")
    long countWithRole(@org.springframework.data.repository.query.Param("role") UserAccount.Role role);
}
