package com.sjinc.trendanalysis.repository;

import com.sjinc.trendanalysis.domain.TrendWeekCloseDoc;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;

public interface TrendWeekCloseDocRepository extends JpaRepository<TrendWeekCloseDoc, Long> {

    @Modifying
    @Query("delete from TrendWeekCloseDoc d where d.weekStart = :weekStart")
    int deleteByWeekStart(LocalDate weekStart);
}
