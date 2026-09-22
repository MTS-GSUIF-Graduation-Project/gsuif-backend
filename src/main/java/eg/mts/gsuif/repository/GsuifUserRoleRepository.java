package eg.mts.gsuif.repository;

import eg.mts.gsuif.entity.GsuifUser;
import eg.mts.gsuif.entity.GsuifUserRole;
import eg.mts.gsuif.entity.GsuifUserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GsuifUserRoleRepository extends JpaRepository<GsuifUserRole, GsuifUserRoleId> {
    List<GsuifUserRole> findByUser(GsuifUser user);
}
