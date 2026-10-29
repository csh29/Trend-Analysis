package com.sjinc.trendanalysis.repository;

import com.sjinc.trendanalysis.domain.RagQueryLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface RagQueryLogRepository extends JpaRepository<RagQueryLog, Long> {

    List<RagQueryLog> findByAskedAtBetweenOrderByAskedAtDesc(LocalDateTime from, LocalDateTime to, Pageable pageable);

    @Modifying
    @Query("DELETE FROM RagQueryLog l WHERE l.askedAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
