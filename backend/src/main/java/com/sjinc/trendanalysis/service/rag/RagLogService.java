package com.sjinc.trendanalysis.service.rag;

import com.sjinc.trendanalysis.domain.RagQueryLog;
import com.sjinc.trendanalysis.dto.rag.RagAnswerView;
import com.sjinc.trendanalysis.dto.rag.RagLogView;
import com.sjinc.trendanalysis.repository.RagQueryLogRepository;
import com.sjinc.trendanalysis.service.analysis.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 챗봇 질문·답 로그(2026-10-29 사용자 요청) — 실제로 무엇을 묻는지, 어느 경로로 답했는지, 토큰·시간, 👍👎를 남긴다.
 * 로그 저장이 실패해도 답은 돌려준다(로그는 부가 기능). 보관 기간이 지난 로그는 매일 지운다.
 */
@Service
@Slf4j
public class RagLogService {

    static final int MAX_TEXT = 600;
    static final int MAX_ROWS = 500;
    private static final Set<String> FEEDBACKS = Set.of(RagQueryLog.UP, RagQueryLog.DOWN);

    private final RagQueryLogRepository repository;
    private final int retentionDays;

    public RagLogService(RagQueryLogRepository repository, @Value("${rag.log.retention-days:180}") int retentionDays) {
        this.repository = repository;
        this.retentionDays = retentionDays;
    }

    /** 로그 ID(저장 실패면 null — 화면은 👍👎를 그리지 않는다). */
    @Transactional
    public Long record(String username, RagAnswerView view, TokenUsage usage, long elapsedMs) {
        try {
            String resolved = view.interpretation() == null ? null : view.interpretation().resolvedQuestion();
            return repository.save(RagQueryLog.builder()
                    .username(username).askedAt(LocalDateTime.now())
                    .question(cut(view.question())).resolvedQuestion(cut(resolved))
                    .route(route(view)).grounded(view.grounded()).reason(view.reason())
                    .answerText(answerText(view))
                    .sourceDocIds(cut(view.sources() == null ? "" : view.sources().stream().map(RagAnswerView.Source::docId)
                            .collect(Collectors.joining(","))))
                    .inputTokens(usage.input() + usage.cacheCreation() + usage.cacheRead()).outputTokens(usage.output())
                    .elapsedMs(elapsedMs)
                    .build()).getId();
        } catch (RuntimeException e) {
            log.warn("챗봇 질문 로그 저장 실패(답은 그대로 보낸다): {}", e.getMessage());
            return null;
        }
    }

    /** 👍👎 — 질문한 사람만 바꿀 수 있다. value가 null이면 지운다. */
    @Transactional
    public void feedback(Long logId, String value, String username) {
        if (value != null && !FEEDBACKS.contains(value)) {
            throw new IllegalArgumentException("피드백은 UP·DOWN이다: " + value);
        }
        RagQueryLog entry = repository.findById(logId == null ? -1L : logId)
                .filter(l -> l.getUsername().equals(username))
                .orElseThrow(() -> new IllegalArgumentException("피드백을 남길 수 없는 질문입니다."));
        entry.giveFeedback(value);
    }

    /** 관리 화면 — 기간(게시일이 아니라 질문한 날) 안의 최근 로그, 피드백·경로로 거른다. 최근 MAX_ROWS건. */
    @Transactional(readOnly = true)
    public List<RagLogView> search(LocalDate from, LocalDate to, String feedback, String route) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("기간이 잘못됐습니다.");
        }
        return repository.findByAskedAtBetweenOrderByAskedAtDesc(start.atStartOfDay(), end.plusDays(1).atStartOfDay(),
                        PageRequest.of(0, MAX_ROWS)).stream()
                .filter(l -> feedback == null || feedback.isBlank() || ("NONE".equals(feedback) ? l.getFeedback() == null
                        : feedback.equals(l.getFeedback())))
                .filter(l -> route == null || route.isBlank() || route.equals(l.getRoute()))
                .map(RagLogView::of)
                .toList();
    }

    /** 보관 기간이 지난 로그를 지운다(매일 새벽 4시). */
    @Scheduled(cron = "${rag.log.purge-cron:0 0 4 * * *}")
    @Transactional
    public void purge() {
        int deleted = repository.deleteOlderThan(LocalDate.now().minusDays(retentionDays).atStartOfDay());
        if (deleted > 0) {
            log.info("보관 기간({}일)이 지난 챗봇 질문 로그 {}건을 지웠습니다.", retentionDays, deleted);
        }
    }

    static String route(RagAnswerView view) {
        if ("OFF_TOPIC".equals(view.reason())) return "OFF_TOPIC";
        if (view.interpretation() != null && "AGENT".equals(view.interpretation().by())) return "AGENT";
        return view.facts() != null || (view.interpretation() != null && view.interpretation().trend()) ? "TREND" : "SEARCH";
    }

    static String answerText(RagAnswerView view) {
        StringBuilder text = new StringBuilder(Objects.toString(view.answer(), ""));
        if (view.body() != null) {
            text.append("\n").append(view.body());
        } else if (view.points() != null) {
            view.points().forEach(p -> text.append("\n- ").append(p.text()));
        }
        return text.toString();
    }

    private static String cut(String value) {
        return value == null || value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT);
    }
}
