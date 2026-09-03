package com.vndirect.poc.merge.repo;

import com.vndirect.poc.merge.domain.Watchlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WatchlistRepository extends JpaRepository<Watchlist, Long> {
    List<Watchlist> findByUserId(Long userId);

    @Modifying
    @Query("update Watchlist w set w.userId = :targetId where w.userId = :sourceId")
    int reassignUserId(@Param("sourceId") Long sourceId, @Param("targetId") Long targetId);
}
