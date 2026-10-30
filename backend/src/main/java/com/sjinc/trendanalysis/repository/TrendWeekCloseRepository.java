package com.sjinc.trendanalysis.repository;

import com.sjinc.trendanalysis.domain.TrendWeekClose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TrendWeekCloseRepository extends JpaRepository<TrendWeekClose, LocalDate> {

    /** 마지막 마감 주(월요일). 마감이 없으면 null. */
    @Query("select max(c.weekStart) from TrendWeekClose c")
    LocalDate findLastWeekStart();

    Optional<TrendWeekClose> findTopByOrderByWeekStartDesc();

    List<TrendWeekClose> findTop12ByOrderByWeekStartDesc();
}
