package com.vndirect.poc.merge.repo;

import com.vndirect.poc.merge.domain.AuthMethod;
import com.vndirect.poc.merge.domain.AuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AuthMethodRepository extends JpaRepository<AuthMethod, Long> {
    List<AuthMethod> findByUserId(Long userId);
    Optional<AuthMethod> findByProviderAndExternalId(AuthProvider provider, String externalId);

    @Modifying
    @Query("update AuthMethod a set a.userId = :targetId where a.userId = :sourceId")
    int reassignUserId(@Param("sourceId") Long sourceId, @Param("targetId") Long targetId);
}
