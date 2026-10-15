package com.taskapp.auth;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Table {@code user_connections} (V7). Lock order (bug B1 rules): every write of a user's connections first locks
 * that user's {@code users} row ({@link UserRepository#findByIdForUpdate}) and runs READ COMMITTED; rows are
 * deleted by primary key only.
 */
public interface UserConnectionRepository extends JpaRepository<UserConnection, Long> {

    /** {@code providerSubject} is compared exactly (ascii_bin). Plain read, no lock. */
    Optional<UserConnection> findByProviderAndProviderSubject(String provider, String providerSubject);

    /** The user's connection for {@code provider}, if any. Plain read, no lock. */
    @Query("select c from UserConnection c where c.user.id = :userId and c.provider = :provider")
    Optional<UserConnection> findByUserIdAndProvider(@Param("userId") long userId,
            @Param("provider") String provider);

    /** Deletes one connection by primary key. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserConnection c where c.id = :id")
    int deleteByIdNow(@Param("id") long id);
}
