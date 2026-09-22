package eg.mts.gsuif.repository;

import eg.mts.gsuif.entity.GsuifUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface GsuifUserRepository extends JpaRepository<GsuifUser, UUID> {
    Optional<GsuifUser> findByUsername(String username);
    boolean existsByUsername(String username);
}
