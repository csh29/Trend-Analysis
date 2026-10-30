package com.sjinc.trendanalysis.repository;

import com.sjinc.trendanalysis.domain.TrendWeekCloseLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrendWeekCloseLogRepository extends JpaRepository<TrendWeekCloseLog, Long> {

    List<TrendWeekCloseLog> findTop20ByOrderByIdDesc();
}
