package com.sjinc.trendanalysis.service.home;

import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.TrendItemValue;
import com.sjinc.trendanalysis.domain.TrendWeeklyStat;
import com.sjinc.trendanalysis.dto.home.HomeView;
import com.sjinc.trendanalysis.dto.trend.TrendView;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.repository.TrendWeeklyStatRepository;
import com.sjinc.trendanalysis.service.trend.TrendAggregator;
import com.sjinc.trendanalysis.service.trend.TrendQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 홈 대시보드(정의서 화면 "홈": 핵심 지표·주간 급상승 키워드 TOP 5·간단 질문창). 수치는 집계 표만 읽는다 —
 * 기준 주는 집계가 있는 가장 최근 주다(트렌드 분석 화면의 기본값인 "지난주"와 다를 수 있다. 자료가 이번 주에만 있으면 이번 주).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HomeService {

    static final int TOP_SIZE = 5;

    private final TrendQueryService trendQueryService;
    private final TrendWeeklyStatRepository statRepository;
    private final SourceDocumentRepository sourceDocumentRepository;

    public HomeView getHome() {
        long done = sourceDocumentRepository.countByStatus(SourceDocument.DONE);
        long pending = sourceDocumentRepository.countByStatus(SourceDocument.PENDING)
                + sourceDocumentRepository.countByStatus(SourceDocument.PROCESSING);
        long failed = sourceDocumentRepository.countByStatus(SourceDocument.FAILED);

        LocalDate week = statRepository.findLastWeekStart();
        if (week == null) {
            return new HomeView(null, false, 0, 0, 0, List.of(), done, pending, failed, List.of());
        }
        TrendView keywords = trendQueryService.getTrend(TrendItemValue.KEYWORD, null, week, 1);
        TrendView categories = trendQueryService.getTrend(TrendWeeklyStat.CATEGORY, null, week, 1);
        boolean current = week.equals(TrendAggregator.weekStart(LocalDate.now()));
        return new HomeView(week, current, keywords.surgeCount(), keywords.newCount(),
                keywords.rows().size(), bars(keywords),
                done, pending, failed, bars(categories));
    }

    private static List<HomeView.Bar> bars(TrendView view) {
        return view.rows().stream().limit(TOP_SIZE)
                .map(r -> new HomeView.Bar(r.label(), r.count(), r.surge()))
                .toList();
    }
}
