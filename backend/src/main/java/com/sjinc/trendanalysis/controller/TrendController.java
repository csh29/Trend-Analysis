package com.sjinc.trendanalysis.controller;

import com.sjinc.trendanalysis.domain.ProgramButton;
import com.sjinc.trendanalysis.domain.TrendWeekClose;
import com.sjinc.trendanalysis.dto.trend.TrendCloseView;
import com.sjinc.trendanalysis.dto.trend.TrendEvidenceView;
import com.sjinc.trendanalysis.dto.trend.TrendView;
import com.sjinc.trendanalysis.security.ProgramAccessGuard;
import com.sjinc.trendanalysis.security.RequiresProgram;
import com.sjinc.trendanalysis.service.trend.TrendAggregationService;
import com.sjinc.trendanalysis.service.trend.TrendAggregator;
import com.sjinc.trendanalysis.service.trend.TrendQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

/** 트렌드 분석 화면(P-05)이 호출하는 REST API. "trend-analysis" 권한이 있어야 한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/trends")
@RequiresProgram("trend-analysis")
public class TrendController {

    private static final String PROGRAM_ID = "trend-analysis";

    private final TrendQueryService trendQueryService;
    private final TrendAggregationService trendAggregationService;
    private final ProgramAccessGuard programAccessGuard;

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

    /** 배치(일 1회)를 기다리지 않고 지금 다시 집계한다 — 분석이 막 끝난 문서를 바로 보고 싶을 때. 마감된 주는 다시 세지 않는다. */
    @PostMapping("/aggregate")
    public TrendAggregationService.Result aggregate(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        // 화면이 보고 있는 주를 넘기면, 그 주가 마감됐을 때 거부한다(2026-10-30 — 마감된 주를 보며 눌러도 "집계했습니다"가 떠 헷갈렸다)
        if (weekStart != null && trendAggregationService.assignment().isClosed(TrendAggregator.weekStart(weekStart))) {
            throw new IllegalArgumentException("마감된 주는 집계할 수 없습니다. 다시 집계하려면 먼저 마감 취소하세요.");
        }
        return trendAggregationService.aggregate();
    }

    /** 주 마감 현황(마지막 마감 주·최근 마감·이력). */
    @GetMapping("/closes")
    public TrendCloseView getCloseStatus() {
        return trendQueryService.getCloseStatus();
    }

    /** weekStart 주까지 마감한다(앞의 열린 주도 같이). 버튼 "마감"(기타2) 권한이 있어야 한다. */
    @PostMapping("/closes")
    public List<LocalDate> close(@RequestBody CloseRequest request, Principal principal) {
        programAccessGuard.requireButton(principal.getName(), PROGRAM_ID, ProgramButton.ETC2);
        if (request == null || request.weekStart() == null) {
            throw new IllegalArgumentException("마감할 주를 지정하세요.");
        }
        return trendAggregationService.close(request.weekStart(), principal.getName()).stream().map(TrendWeekClose::getWeekStart).toList();
    }

    /** 가장 최근 마감 주의 마감을 취소한다(사유 필수). 버튼 "마감 취소"(기타3) 권한이 있어야 한다. */
    @PostMapping("/closes/cancel")
    public LocalDate cancelClose(@RequestBody CancelRequest request, Principal principal) {
        programAccessGuard.requireButton(principal.getName(), PROGRAM_ID, ProgramButton.ETC3);
        return trendAggregationService.cancelLastClose(principal.getName(), request == null ? null : request.reason());
    }

    public record CloseRequest(LocalDate weekStart) {
    }

    public record CancelRequest(String reason) {
    }
}
