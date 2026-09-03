package com.vndirect.poc.merge.repo;

import com.vndirect.poc.merge.domain.TradeOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TradeOrderRepository extends JpaRepository<TradeOrder, Long> {
    List<TradeOrder> findByUserId(Long userId);

    @Modifying
    @Query("update TradeOrder t set t.userId = :targetId where t.userId = :sourceId")
    int reassignUserId(@Param("sourceId") Long sourceId, @Param("targetId") Long targetId);
}
