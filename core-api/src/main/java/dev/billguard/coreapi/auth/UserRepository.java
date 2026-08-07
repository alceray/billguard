package dev.billguard.coreapi.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByAuth0Sub(String auth0Sub);

    @Query(value = """
        INSERT INTO users (auth0_sub, email, name)
        VALUES (:sub, :email, :name)
        ON CONFLICT (auth0_sub) DO UPDATE
          SET email = EXCLUDED.email,
              name = COALESCE(EXCLUDED.name, users.name),
              updated_at = NOW()
        RETURNING id, email, name, created_at
        """, nativeQuery = true)
    UserProjection upsert(
        @Param("sub") String subject,
        @Param("email") String email,
        @Param("name") String name
    );
}
