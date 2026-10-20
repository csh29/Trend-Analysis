package com.sjinc.trendanalysis.controller;

import com.sjinc.trendanalysis.dto.trend.TrendEvidenceView;
import com.sjinc.trendanalysis.dto.trend.TrendView;
import com.sjinc.trendanalysis.security.RequiresProgram;
import com.sjinc.trendanalysis.service.trend.TrendAggregationService;
import com.sjinc.trendanalysis.service.trend.TrendQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 트렌드 분석 화면(P-05)이 호출하는 REST API. "trend-analysis" 권한이 있어야 한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/trends")
@RequiresProgram("trend-analysis")
public class TrendController {

    private final TrendQueryService trendQueryService;
    private final TrendAggregationService trendAggregationService;

    @GetMapping
    public TrendView getTrend(@RequestParam(defaultValue = "KEYWORD") String type,
                              @RequestParam(required = false) String category,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate baseWeek,
                              @RequestParam(defaultValue = "8") int weeks) {
        return trendQueryService.getTrend(type, category, baseWeek, weeks);
    }

    @GetMapping("/evidence")
    public List<TrendEvidenceView> getEvidence(@RequestParam String type, @RequestParam String value,
                                               @RequestParam(required = false) String category,
                                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return trendQueryService.getEvidence(type, value, category, from, to);
    }

    /** 배치(일 1회)를 기다리지 않고 지금 다시 집계한다 — 분석이 막 끝난 문서를 바로 보고 싶을 때. */
    @PostMapping("/aggregate")
    public TrendAggregationService.Result aggregate() {
        return trendAggregationService.aggregate();
    }
}
