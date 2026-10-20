package com.sjinc.trendanalysis.repository;

import com.sjinc.trendanalysis.domain.TrendWeeklyStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface TrendWeeklyStatRepository extends JpaRepository<TrendWeeklyStat, Long> {

    List<TrendWeeklyStat> findByCategoryAndValueTypeAndWeekStartBetween(
            String category, String valueType, LocalDate from, LocalDate to);

    @Query("select max(s.aggregatedAt) from TrendWeeklyStat s")
    LocalDateTime findLastAggregatedAt();
}
