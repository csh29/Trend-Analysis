package com.sjinc.trendanalysis.service.rag;

import com.sjinc.trendanalysis.domain.ComCd;
import com.sjinc.trendanalysis.domain.SourceDocument;
import com.sjinc.trendanalysis.domain.TrendItem;
import com.sjinc.trendanalysis.domain.TrendItemValue;
import com.sjinc.trendanalysis.domain.TrendWeeklyStat;
import com.sjinc.trendanalysis.repository.ComCdRepository;
import com.sjinc.trendanalysis.repository.SourceDocumentRepository;
import com.sjinc.trendanalysis.repository.TrendItemRepository;
import com.sjinc.trendanalysis.service.analysis.DocumentService;
import com.sjinc.trendanalysis.service.analysis.TrendCodes;
import com.sjinc.trendanalysis.service.trend.TrendAggregationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 유행 질문의 근거 집계 — 유행 = 많이 입어 대중화된 것(2026-10-22 사용자 정의)이라, 질문 범위(카테고리·아이템 종류)의 원문을
 * 기간 안에서 센다. 질문할 때 바로 센다(주간 배치를 기다리지 않는다 — 방금 올린 원문도 반영).
 *
 * <ul>
 *   <li>원문 수: 그 값이 나온 분석 완료 원문 수(한 원문에 여러 번 나와도 1). 매체 수: 그 원문들의 서로 다른 출처 수 — 한 매체가
 *       여러 번 올린 것과 여러 곳에서 보이는 것(대중화)을 가르기 위해서다. 같은 원문이 다시 수집된 것은 저장 단계에서 URL로 막는다.</li>
 *   <li>기간: 질문이 정한 기간, 없으면 오늘까지 4주. 그 안에 원문이 없으면 범위 원문 중 가장 최근 게시일까지 4주로 옮기고(8주 넘게
 *       오래됐으면 stale), 직전 같은 길이 기간과 비교한다.</li>
 *   <li>예시 원문: 첫 번째 축(아이템 종류, 없으면 카테고리) 상위 값마다 최근 원문 2건 — 답변의 출처가 된다.</li>
 *   <li>기타(OUTER_ETC·ETC 등): 목록에 없는 옷이 한데 모여 "기타 아우터 7건"만으로는 무엇이 뜨는지 알 수 없다 — 그 안의
 *       아이템명(Claude 자유 문장)을 원문 수로 세어 상위 몇 개를 같이 준다(2026-10-23 사용자 결정. 새 종류 코드를 만들기 전에도 답이 비지 않게).</li>
 *   <li>질문 단어(해석이 코드로 못 옮긴 말 — 무스탕·발마칸…): 키워드·아이템명에 그 말이 들어간 원문을 센다(2026-10-23 사용자 결정).
 *       코드가 없으면 해석이 그 말을 버려 "무스탕 유행해?"가 "아우터 유행"으로 넓어졌다. 범위(카테고리)와 상관없이 세고, 실제로 나온
 *       표현을 같이 준다 — "무스탕 칼라"처럼 옷 자체가 아닌 언급도 걸리므로 옷 개수가 아니라 언급 수다. 그 원문을 예시 원문 앞에 둔다.
 *       여러 낱말이면 낱말이 모두 들어 있으면 걸린다("와이드 팬츠" → "와이드 데님 팬츠"도 — 2026-10-26).</li>
 *   <li>질문 단어의 포인트: 아이템명에 그 말이 들어간 옷(대상 옷)의 컬러·소재·실루엣을 원문 수로 세어 직전과 비교하고, 같은 원문에
 *       함께 나온 다른 옷 종류(매치)와 키워드(스타일)를 센다 — "레더 재킷 9건"만으로는 어떤 레더 재킷이 뜨는지 알 수 없었다(2026-10-26 사용자 요청).
 *       키워드에만 나온 원문은 대상 옷을 몰라 포인트에서 빠진다.</li>
 *   <li>시즌(질문이 F/W·올가을처럼 시즌을 가리킬 때): 원문의 시즌 값(정형화 seasons)이 그 시즌인 원문만 센다 — 게시일 기간(6개월)만으로는
 *       S/S 자료가 섞인다. 시즌 값이 없는 원문은 빠진다(2026-10-23 사용자 결정).</li>
 *   <li>속성 조건(해석의 filters — "와이드 팬츠" → 하의 + 실루엣 WIDE, "여성 아우터" → 아우터 + 여성복): 그 값을 가진 의류 아이템만 범위로 센다. 종류마다 값 하나라도
 *       맞으면 되고(OR), 종류끼리는 모두 맞아야 한다(AND). 대상 이름 뒤에 " · 와이드"처럼 붙는다(2026-10-26 — 글자 비교로는 "와이드 데님 팬츠"가 빠졌다).</li>
 *   <li>묻는 축(해석의 focus — "요즘 유행하는 핏은?"이면 실루엣)이 있으면 그 축이 첫 축이 된다 — 화면의 막대와 예시 원문이 그 축의
 *       상위 값으로 나온다. 없으면 아이템 종류(종류를 콕 집었으면 소재)가 첫 축이다(2026-10-23).</li>
 *   <li>소재·컬러·실루엣 값마다 그 값으로 나온 아이템 종류 상위(같은 의류 아이템 기준)를 같이 센다 — "컬러별 활용하기 좋은 아이템" 같은
 *       교차 질문의 근거다. 종류를 콕 집은 질문은 종류가 하나라 세지 않는다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TrendFactsService {

    static final int TOP = 5;
    /** 도구 경로의 count_trend가 고를 수 있는 축별 상위 개수 상한 — "패딩 제외하고" 같은 질문은 Claude가 넉넉히 받아 직접 뺀다. */
    static final int MAX_TOP = 15;
    static final int EXAMPLE_VALUES = 3;
    static final int EXAMPLES_PER_VALUE = 2;
    static final int MAX_EXAMPLES = 6;
    static final int STALE_DAYS = 56;
    static final int ETC_NAMES = 3;
    static final int CROSS_ITEMS = 3;
    static final int TERM_VALUES = 3;
    static final int EXAMPLES_PER_TERM = 3;
    /**
     * 직전 대비 증감률 — "+200%"·"-50%"·"0%"(반올림), 직전 0건이면 "신규", 둘 다 0이면 "-". 계산은 여기서 한다 — Claude가 직접 나누면
     * 틀릴 수 있어 집계 줄과 화면에 완성된 값을 준다(2026-10-28 사용자 요청: 증감 질문은 기준 대비 몇 % 늘었는지·줄었는지로 답한다).
     */
    public static String rate(int count, int prevCount) {
        if (prevCount == 0) return count == 0 ? "-" : "신규";
        long pct = Math.round((count - prevCount) * 100.0 / prevCount);
        return (pct > 0 ? "+" : "") + pct + "%";
    }

    /** 같은 출처의 두 번째부터 원문 하나의 유행점수 — 한 곳의 반복 게시는 여러 곳에서 보이는 것(대중화)보다 약한 신호다. */
    static final double REPEAT_WEIGHT = 0.5;

    /**
     * 유행점수 = 출처 수 + 0.5 × (원문 수 − 출처 수). 출처마다 첫 원문은 1점, 같은 출처의 반복 원문은 0.5점(2026-10-28 사용자 결정:
     * 유행 질문은 이 점수로 판단한다 — 한 매체가 10번 올린 것(5.5)보다 5곳이 2번씩 다룬 것(7.5)이 높다).
     */
    public static double trendScore(int docCount, int sourceCount) {
        return sourceCount + REPEAT_WEIGHT * (docCount - sourceCount);
    }

    /**
     * 유행점수에 쓰는 출처 — 매체명. 직접 업로드(이미지)·매체 미입력(텍스트) 원문은 출처를 몰라 원문마다 다른 출처로 본다
     * (이미지만 있는 원문은 인스타 게시물로 간주 — 계정을 모르니 반복으로 깎지 않는다).
     */
    static String sourceKey(SourceDocument doc) {
        String media = doc.getMedia();
        return media == null || media.isBlank() || DocumentService.UPLOAD_MEDIA.equals(media) || DocumentService.TEXT_MEDIA.equals(media)
                ? "doc:" + doc.getId() : media;
    }

    /** 질문 단어 대상 옷에서 세는 축 — 어떤 레더 재킷이 뜨는지(색·소재·핏). */
    static final List<String> TERM_POINTS = List.of(TrendItemValue.COLOR, TrendItemValue.PATTERN, TrendItemValue.MATERIAL, TrendItemValue.SILHOUETTE,
            TrendItemValue.DETAIL);

    private final SourceDocumentRepository sourceDocumentRepository;
    private final TrendItemRepository trendItemRepository;
    private final ComCdRepository comCdRepository;

    /**
     * 값 하나의 집계. prevCount는 직전 같은 길이 기간의 원문 수. names는 기타 값일 때만 — 그 안의 아이템명 상위(원문 수 순).
     * items는 소재·컬러·실루엣 값일 때만 — 그 값으로 나온 아이템 종류 이름 상위(원문 수 순). score는 유행점수(trendScore).
     */
    public record Row(String value, String label, int count, int mediaCount, int prevCount, List<NameCount> names,
                      List<NameCount> items, double score) {
    }

    /** 기타 안의 아이템명 하나와 그 이름이 나온 원문 수. */
    public record NameCount(String name, int count) {
    }

    /**
     * 질문 단어 하나의 언급 집계. expressions는 그 말이 들어간 키워드·아이템명 상위(원문 수 순).
     * points는 대상 옷의 컬러·소재·실루엣 축, together는 같은 원문에 함께 나온 옷 종류, keywords는 그 원문들의 다른 키워드(모두 기간 안 원문 수 순).
     */
    public record TermRow(String term, int count, int mediaCount, int prevCount, List<NameCount> expressions,
                          List<TermPoint> points, List<NameCount> together, List<NameCount> keywords) {
    }

    /** 질문 단어 대상 옷의 축 하나(컬러 등)와 그 값들. */
    public record TermPoint(String label, List<ValueCount> values) {
    }

    /** 값 하나 — 기간 안 원문 수와 직전 기간 원문 수. */
    public record ValueCount(String name, int count, int prevCount) {
    }

    /** 축 하나(아이템 종류·소재…)의 상위 값들. */
    public record Dimension(String valueType, String label, List<Row> rows) {
    }

    /**
     * @param scopeLabel 집계 범위(예: "아우터", "아우터 · 패딩", 범위가 없으면 "전체")
     * @param docCount   기간 안 범위 원문 수
     * @param prevDocCount 직전 기간 범위 원문 수
     * @param change     증감 질문의 방향(UP·DOWN·BOTH) — 있으면 축마다 값이 증감 순이다. 없으면 null(원문 수 순)
     */
    public record Facts(QuestionInterpreter.Period period, boolean stale, String scopeLabel, int docCount, int mediaCount,
                        int prevDocCount, List<Dimension> dimensions, List<TermRow> terms, List<String> evidenceDocIds,
                        boolean focused, String change) {

        /**
         * AI 서비스에 보내는 집계 줄(한국어) — 해석 규칙은 prompts/rag/answer.md. 묻는 축이 정해졌으면(focused) 그 축(첫 축)만 보낸다 —
         * "F/W 핵심 컬러" 답은 컬러 축과 그 주요 아이템만 썼고 나머지 축 줄 약 900토큰은 쓰이지 않았다(2026-10-26 사용자 결정).
         * 묻는 축이 없는 질문(기획 브리프 등)은 여러 축이 필요해 모두 보낸다.
         */
        public List<String> lines() {
            List<String> lines = new ArrayList<>();
            lines.add("대상: " + scopeLabel + " · 원문 " + docCount + "건 · 매체 " + mediaCount + "곳 (직전 기간 원문 " + prevDocCount + "건, "
                    + rate(docCount, prevDocCount) + ")");
            if (change == null) {
                lines.add("[정렬] 유행점수 순 — 유행점수 = 출처마다 첫 원문 1점 + 같은 출처의 반복 원문 0.5점(직접 업로드는 원문마다 다른 출처)");
            } else {
                lines.add("[정렬] " + switch (change) {
                    case "UP" -> "직전 대비 늘어난 값만, 늘어난 원문 수가 큰 순";
                    case "DOWN" -> "직전 대비 줄어든 값만, 줄어든 원문 수가 큰 순(이번 기간 0건 포함)";
                    default -> "직전 대비 달라진 값만, 변화한 원문 수가 큰 순";
                } + " — 축에 값이 없으면 그런 값이 없다는 뜻");
            }
            for (Dimension d : focused ? dimensions.stream().limit(1).toList() : dimensions) {
                if (d.rows().isEmpty()) continue;
                lines.add("[" + d.label() + "] " + d.rows().stream()
                        .map(r -> r.label() + " 유행점수 " + score(r.score()) + " · " + r.count() + "건 · 매체 " + r.mediaCount() + "곳 · 직전 " + r.prevCount() + "건("
                                + rate(r.count(), r.prevCount()) + ")"
                                + (r.names().isEmpty() ? "" : " (아이템명: " + r.names().stream()
                                .map(n -> n.name() + " " + n.count() + "건").collect(Collectors.joining(", ")) + ")")
                                + (r.items().isEmpty() ? "" : " [주요 아이템: " + r.items().stream()
                                .map(n -> n.name() + " " + n.count() + "건").collect(Collectors.joining(", ")) + "]"))
                        .collect(Collectors.joining(" / ")));
            }
            for (TermRow t : terms) {
                lines.add("[질문 단어 — 키워드·아이템명에 나온 원문, 범위 무관] " + t.term() + " " + t.count() + "건 · 매체 " + t.mediaCount()
                        + "곳 · 직전 " + t.prevCount() + "건(" + rate(t.count(), t.prevCount()) + ")" + (t.expressions().isEmpty() ? "" : " (나온 표현: " + t.expressions().stream()
                        .map(n -> n.name() + " " + n.count() + "건").collect(Collectors.joining(", ")) + ")"));
                for (TermPoint pt : t.points()) {
                    lines.add("  - " + t.term() + " 옷의 " + pt.label() + ": " + pt.values().stream()
                            .map(v -> v.name() + " " + v.count() + "건(직전 " + v.prevCount() + ", " + rate(v.count(), v.prevCount()) + ")")
                            .collect(Collectors.joining(", ")));
                }
                if (!t.together().isEmpty()) {
                    lines.add("  - " + t.term() + "와 같은 원문에 함께 나온 옷: " + t.together().stream()
                            .map(n -> n.name() + " " + n.count() + "건").collect(Collectors.joining(", ")));
                }
                if (!t.keywords().isEmpty()) {
                    lines.add("  - " + t.term() + " 원문의 다른 키워드: " + t.keywords().stream()
                            .map(n -> n.name() + " " + n.count() + "건").collect(Collectors.joining(", ")));
                }
            }
            return lines;
        }
    }

    public Facts count(QuestionInterpreter.Interpretation question, LocalDate today) {
        return count(question, today, TOP);
    }

    /** @param top 축마다 고를 상위 값 개수(1~MAX_TOP) */
    public Facts count(QuestionInterpreter.Interpretation question, LocalDate today, int top) {
        Scope scope = new Scope(question.category(), question.itemType(), Set.copyOf(question.seasons()), question.focus(),
                question.filters(), Math.max(1, Math.min(MAX_TOP, top)));
        // 시즌을 정했는데 기간이 비었으면(해석이 가끔 빠뜨린다) 시즌 기간 — 최근 4주로 세면 F/W 자료 대부분이 빠진다
        QuestionInterpreter.Period period = question.period() != null ? question.period()
                : !question.seasons().isEmpty() ? QuestionInterpreter.seasonWindow(today) : QuestionInterpreter.recentWeeks(today);
        Facts facts = countIn(period, scope, question, false);
        if (facts.docCount() == 0 && question.period() == null) {
            // 최근 4주에 없으면 범위 원문 중 가장 최근 게시일까지 4주로 옮긴다(오래된 자료라고 알린다)
            LocalDate latest = latestDate(scope);
            if (latest != null) {
                boolean stale = ChronoUnit.DAYS.between(latest, today) > STALE_DAYS;
                QuestionInterpreter.Period moved = new QuestionInterpreter.Period(latest.minusDays(QuestionInterpreter.TREND_DAYS - 1L),
                        latest, "가장 최근 자료 4주", false);
                facts = countIn(moved, scope, question, stale);
            }
        }
        return facts;
    }

    private record Scope(String category, String itemType, Set<String> seasons, String focus, Map<String, List<String>> filters, int top) {
        boolean matches(TrendItem.Garment g) {
            return (category == null || category.equals(g.category())) && (itemType == null || itemType.equals(g.type()))
                    && filters.entrySet().stream().allMatch(f -> valuesOf(g, f.getKey()).stream().anyMatch(f.getValue()::contains));
        }

        /** 질문이 시즌을 정했으면 원문의 시즌 값 중 하나가 그 시즌이어야 한다(시즌 값이 없는 원문은 빠진다). */
        boolean inSeason(TrendItem item) {
            return seasons.isEmpty() || item.valuesOf(TrendItemValue.SEASON).stream().anyMatch(seasons::contains);
        }
    }

    /** 값 하나를 센 것 — 기간 안 원문·매체, 직전 기간 원문. */
    private static final class Tally {
        final Set<Long> docs = new HashSet<>();
        final Set<String> media = new HashSet<>();
        final Set<String> sources = new HashSet<>(); // 유행점수용 출처(sourceKey)
        final Set<Long> prevDocs = new HashSet<>();
        final List<SourceDocument> examples = new ArrayList<>();
        final Map<String, Set<Long>> names = new LinkedHashMap<>(); // 기타 값일 때만 — 아이템명별 원문(기간 안)
        final Map<String, Set<Long>> items = new LinkedHashMap<>(); // 소재·컬러·실루엣 값일 때만 — 아이템 종류별 원문(기간 안)
    }

    private Facts countIn(QuestionInterpreter.Period period, Scope scope, QuestionInterpreter.Interpretation question, boolean stale) {
        long days = ChronoUnit.DAYS.between(period.from(), period.to()) + 1;
        LocalDate prevFrom = period.from().minusDays(days);
        List<SourceDocument> docs = sourceDocumentRepository.findByStatusAndPublishedAtBetween(SourceDocument.DONE, prevFrom, period.to());
        Map<Long, TrendItem> items = trendItemRepository.findByDocumentIdIn(docs.stream().map(SourceDocument::getId).toList())
                .stream().collect(Collectors.toMap(TrendItem::getDocumentId, Function.identity()));

        List<String> types = dimensionTypes(scope);
        Map<String, Map<String, Tally>> tallies = new LinkedHashMap<>();
        types.forEach(t -> tallies.put(t, new LinkedHashMap<>()));
        Set<Long> scopeDocs = new HashSet<>();
        Set<String> scopeMedia = new HashSet<>();
        Set<Long> prevScopeDocs = new HashSet<>();

        // 최신 원문부터 — 예시 원문이 최근 것이 되게
        docs.sort(Comparator.comparing(TrendAggregationService::statDate).reversed().thenComparing(SourceDocument::getId, Comparator.reverseOrder()));
        for (SourceDocument doc : docs) {
            TrendItem item = items.get(doc.getId());
            if (item == null || !scope.inSeason(item)) continue;
            List<TrendItem.Garment> garments = item.garments().stream().filter(scope::matches).toList();
            if (garments.isEmpty()) continue;
            LocalDate date = TrendAggregationService.statDate(doc);
            boolean current = !date.isBefore(period.from());
            if (current) {
                scopeDocs.add(doc.getId());
                scopeMedia.add(doc.getMedia());
            } else {
                prevScopeDocs.add(doc.getId());
            }
            for (String type : types) {
                Set<String> values = new LinkedHashSet<>();
                garments.forEach(g -> values.addAll(valuesOf(g, type)));
                for (String value : values) {
                    Tally t = tallies.get(type).computeIfAbsent(value, v -> new Tally());
                    if (current) {
                        if (t.docs.add(doc.getId())) {
                            t.examples.add(doc);
                        }
                        t.media.add(doc.getMedia());
                        t.sources.add(sourceKey(doc));
                        if (scope.itemType() == null && isAttribute(type)) { // 같은 의류 아이템의 종류 — 컬러별 아이템
                            garments.stream().filter(g -> g.type() != null && valuesOf(g, type).contains(value))
                                    .forEach(g -> t.items.computeIfAbsent(g.type(), k -> new HashSet<>()).add(doc.getId()));
                        }
                        if (isEtc(type, value)) {
                            garments.stream().filter(g -> valuesOf(g, type).contains(value)).map(g -> itemName(g.name()))
                                    .filter(n -> !n.isEmpty()).forEach(n -> t.names.computeIfAbsent(n, k -> new HashSet<>()).add(doc.getId()));
                        }
                    } else {
                        t.prevDocs.add(doc.getId());
                    }
                }
            }
        }

        Map<String, String> typeLabels = names(TrendCodes.VALUE_TYPE);
        Map<String, String> itemTypeNames = names(TrendCodes.ITEM_TYPE);
        List<Dimension> dimensions = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        for (String type : types) {
            Map<String, String> labels = names(TrendWeeklyStat.CATEGORY.equals(type) ? TrendCodes.CATEGORY : TrendCodes.VALUE_TYPE_GROUPS.get(type));
            List<Map.Entry<String, Tally>> ranked = rank(tallies.get(type), question.change(), scope.top());
            dimensions.add(new Dimension(type, typeLabels.getOrDefault(type, type), ranked.stream()
                    .map(e -> new Row(e.getKey(), labels.getOrDefault(e.getKey(), e.getKey()), e.getValue().docs.size(),
                            e.getValue().media.size(), e.getValue().prevDocs.size(), topNames(e.getValue().names),
                            topNames(e.getValue().items, CROSS_ITEMS).stream()
                                    .map(n -> new NameCount(shortName(itemTypeNames.getOrDefault(n.name(), n.name())), n.count())).toList(),
                            score(e.getValue())))
                    .toList()));
            if (evidence.isEmpty() && !ranked.isEmpty()) { // 첫 번째 축의 상위 값마다 최근 원문
                ranked.stream().limit(EXAMPLE_VALUES).forEach(e -> e.getValue().examples.stream().limit(EXAMPLES_PER_VALUE)
                        .map(SourceDocument::getDocId).filter(id -> !evidence.contains(id)).forEach(evidence::add));
            }
        }
        if (scope.itemType() != null || evidence.isEmpty()) { // 종류를 콕 집었거나 상위 값에 이번 기간 원문이 없으면(감소 질문) 범위의 최근 원문
            evidence.clear();
            docs.stream().filter(d -> scopeDocs.contains(d.getId())).limit(MAX_EXAMPLES).map(SourceDocument::getDocId).forEach(evidence::add);
        }
        List<TermRow> terms = new ArrayList<>();
        List<String> termEvidence = new ArrayList<>();
        for (String term : question.terms()) {
            List<String> keys = keys(term);
            if (keys.isEmpty()) continue;
            Tally t = new Tally();
            // 대상 옷의 축별 값 → 기간 안·직전 원문, 함께 나온 옷 종류·다른 키워드 → 기간 안 원문
            Map<String, Map<String, Set<Long>>> pointNow = new LinkedHashMap<>();
            Map<String, Map<String, Set<Long>>> pointPrev = new HashMap<>();
            Map<String, Set<Long>> together = new LinkedHashMap<>();
            Map<String, Set<Long>> keywords = new LinkedHashMap<>();
            for (SourceDocument doc : docs) { // 최신 원문부터
                TrendItem item = items.get(doc.getId());
                List<String> hits = item == null || !scope.inSeason(item) ? List.of() : mentions(item, keys);
                if (hits.isEmpty()) continue;
                boolean current = !TrendAggregationService.statDate(doc).isBefore(period.from());
                List<TrendItem.Garment> targets = item.garments().stream().filter(g -> matches(g.name(), keys)).toList();
                Map<String, Map<String, Set<Long>>> into = current ? pointNow : pointPrev;
                for (String type : TERM_POINTS) {
                    targets.forEach(g -> valuesOf(g, type).forEach(v -> into.computeIfAbsent(type, k -> new LinkedHashMap<>())
                            .computeIfAbsent(v, k -> new HashSet<>()).add(doc.getId())));
                }
                if (!current) {
                    t.prevDocs.add(doc.getId());
                    continue;
                }
                t.docs.add(doc.getId());
                t.media.add(doc.getMedia());
                t.examples.add(doc);
                hits.forEach(h -> t.names.computeIfAbsent(h, k -> new HashSet<>()).add(doc.getId()));
                if (!targets.isEmpty()) {
                    item.garments().stream().filter(g -> !targets.contains(g) && g.type() != null)
                            .forEach(g -> together.computeIfAbsent(g.type(), k -> new HashSet<>()).add(doc.getId()));
                }
                item.valuesOf(TrendItemValue.KEYWORD).stream().filter(k -> k != null && !matches(k, keys)).map(TrendFactsService::itemName)
                        .filter(k -> !k.isEmpty()).forEach(k -> keywords.computeIfAbsent(k, x -> new HashSet<>()).add(doc.getId()));
            }
            List<TermPoint> points = new ArrayList<>();
            for (String type : TERM_POINTS) {
                Map<String, Set<Long>> now = pointNow.getOrDefault(type, Map.of());
                if (now.isEmpty()) continue;
                Map<String, String> labels = names(TrendCodes.VALUE_TYPE_GROUPS.get(type));
                Map<String, Set<Long>> prev = pointPrev.getOrDefault(type, Map.of());
                points.add(new TermPoint(typeLabels.getOrDefault(type, type), topNames(now, TERM_VALUES).stream()
                        .map(n -> new ValueCount(shortName(labels.getOrDefault(n.name(), n.name())), n.count(),
                                prev.getOrDefault(n.name(), Set.of()).size())).toList()));
            }
            terms.add(new TermRow(term, t.docs.size(), t.media.size(), t.prevDocs.size(), topNames(t.names), points,
                    topNames(together, TERM_VALUES).stream()
                            .map(n -> new NameCount(shortName(itemTypeNames.getOrDefault(n.name(), n.name())), n.count())).toList(),
                    topNames(keywords, TERM_VALUES)));
            t.examples.stream().limit(EXAMPLES_PER_TERM).map(SourceDocument::getDocId)
                    .filter(id -> !termEvidence.contains(id)).forEach(termEvidence::add);
        }
        // 질문 단어가 나온 원문을 앞에 — 답이 그 말을 다룰 근거가 된다
        List<String> merged = new ArrayList<>(termEvidence);
        evidence.stream().filter(id -> !merged.contains(id)).forEach(merged::add);
        return new Facts(period, stale, scopeLabel(question) + filterLabel(scope) + seasonLabel(scope), scopeDocs.size(), scopeMedia.size(), prevScopeDocs.size(),
                dimensions, terms, merged.stream().limit(MAX_EXAMPLES).toList(),
                scope.focus() != null && !dimensions.isEmpty() && scope.focus().equals(dimensions.get(0).valueType()),
                question.change());
    }

    /**
     * 축 하나의 상위 값. 보통은 유행점수 순(같으면 원문 수 순). 증감 질문이면 직전 대비 증감(원문 수 차이) 순 — UP은 늘어난 값만, DOWN은 줄어든 값만
     * (이번 기간에 사라진 값 포함), BOTH는 달라진 값을 변화 크기 순으로. 차이가 같으면 이번 기간 원문 수가 많은 순.
     */
    private static List<Map.Entry<String, Tally>> rank(Map<String, Tally> tallies, String change, int top) {
        Comparator<Map.Entry<String, Tally>> byCount = Comparator.comparingDouble((Map.Entry<String, Tally> e) -> score(e.getValue())).reversed()
                .thenComparing(e -> e.getValue().docs.size(), Comparator.reverseOrder())
                .thenComparing(e -> e.getValue().media.size(), Comparator.reverseOrder())
                .thenComparing(Map.Entry::getKey);
        if (change == null) {
            return tallies.entrySet().stream().filter(e -> !e.getValue().docs.isEmpty()).sorted(byCount).limit(top).toList();
        }
        java.util.function.ToIntFunction<Map.Entry<String, Tally>> delta = e -> e.getValue().docs.size() - e.getValue().prevDocs.size();
        return tallies.entrySet().stream()
                .filter(e -> switch (change) {
                    case "UP" -> delta.applyAsInt(e) > 0;
                    case "DOWN" -> delta.applyAsInt(e) < 0;
                    default -> delta.applyAsInt(e) != 0;
                })
                .sorted(Comparator.comparingInt((Map.Entry<String, Tally> e) -> Math.abs(delta.applyAsInt(e))).reversed().thenComparing(byCount))
                .limit(top).toList();
    }

    /**
     * 범위에 따라 셀 축 — 종류를 콕 집었으면 종류 축은 빼고, 카테고리를 정했으면 카테고리 축은 뺀다. 묻는 축이 남아 있으면 맨 앞으로
     * (첫 축이 판단 근거의 막대·예시 원문이 된다).
     */
    private static List<String> dimensionTypes(Scope scope) {
        List<String> types = new ArrayList<>();
        if (scope.category() == null) types.add(TrendWeeklyStat.CATEGORY);
        if (scope.itemType() == null) types.add(TrendItemValue.ITEM_TYPE);
        types.addAll(List.of(TrendItemValue.MATERIAL, TrendItemValue.COLOR, TrendItemValue.PATTERN, TrendItemValue.SILHOUETTE,
                TrendItemValue.DETAIL));
        if (scope.category() == null && scope.itemType() == null) { // 전체를 물으면 종류가 먼저(카테고리보다 구체적)
            types.remove(TrendWeeklyStat.CATEGORY);
            types.add(1, TrendWeeklyStat.CATEGORY);
        }
        if (scope.focus() != null && types.remove(scope.focus())) {
            types.add(0, scope.focus());
        }
        return types;
    }

    private static double score(Tally t) {
        return trendScore(t.docs.size(), t.sources.size());
    }

    /** 유행점수 표기 — 정수면 "5", 아니면 "5.5". */
    static String score(double score) {
        return score == Math.rint(score) ? String.valueOf((long) score) : String.valueOf(score);
    }

    private static boolean isAttribute(String type) {
        return TrendItemValue.MATERIAL.equals(type) || TrendItemValue.COLOR.equals(type) || TrendItemValue.SILHOUETTE.equals(type)
                || TrendItemValue.PATTERN.equals(type) || TrendItemValue.DETAIL.equals(type);
    }

    /** 종류 이름의 괄호 힌트를 뗀다 — "블루종(봄버·항공 점퍼)" → "블루종". */
    private static String shortName(String name) {
        int i = name.indexOf('(');
        return i > 0 ? name.substring(0, i).strip() : name;
    }

    /** 범위 이름 뒤에 붙는 속성 조건(예: " · 와이드", " · 크롭"). 조건이 없으면 빈 문자열. */
    private String filterLabel(Scope scope) {
        StringBuilder label = new StringBuilder();
        scope.filters().forEach((type, values) -> {
            Map<String, String> names = names(TrendCodes.VALUE_TYPE_GROUPS.get(type));
            label.append(" · ").append(values.stream().map(v -> shortName(names.getOrDefault(v, v))).collect(Collectors.joining("·")));
        });
        return label.toString();
    }

    /** 범위 이름 뒤에 붙는 시즌(예: " · 가을·겨울 시즌"). 시즌을 정하지 않았으면 빈 문자열. */
    private String seasonLabel(Scope scope) {
        if (scope.seasons().isEmpty()) return "";
        return " · " + comCdRepository.findByCodeGroupOrderBySortOrderAsc(TrendCodes.SEASON).stream()
                .filter(c -> scope.seasons().contains(c.getCodeValue())).map(ComCd::getCodeName)
                .collect(Collectors.joining("·")) + " 시즌";
    }

    /** 기타 묶음인지 — 카테고리 ETC, 아이템 종류 ETC·OUTER_ETC 같은 카테고리 기타. */
    static boolean isEtc(String type, String value) {
        return (TrendWeeklyStat.CATEGORY.equals(type) || TrendItemValue.ITEM_TYPE.equals(type))
                && ("ETC".equals(value) || value.endsWith("_ETC"));
    }

    /** 질문 단어가 들어간 키워드·아이템명(공백 정리한 표현, 중복 없이). */
    private static List<String> mentions(TrendItem item, List<String> keys) {
        List<String> texts = new ArrayList<>(item.valuesOf(TrendItemValue.KEYWORD));
        item.garments().forEach(g -> texts.add(g.name()));
        return texts.stream().filter(v -> matches(v, keys)).map(TrendFactsService::itemName).distinct().toList();
    }

    /** 질문 단어의 낱말들(공백으로 나눔, 소문자). */
    static List<String> keys(String term) {
        return Arrays.stream(term.strip().split("\\s+")).map(TrendFactsService::compact).filter(k -> !k.isEmpty()).toList();
    }

    /**
     * 글에 질문 단어의 낱말이 모두 들어 있는지 — 공백·대소문자를 무시한다. 낱말마다 따로 보므로 "와이드 팬츠"는 "와이드 데님 팬츠"에도
     * 걸린다(붙여 쓴 "와이드팬츠"도 걸린다).
     */
    static boolean matches(String text, List<String> keys) {
        if (text == null) return false;
        String c = compact(text);
        return keys.stream().allMatch(c::contains);
    }

    private static String compact(String text) {
        return text.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }

    /** 아이템명 비교용 — 앞뒤·중간 공백만 정리한다(표기 흔들림은 그대로 둔다. 같은 이름끼리만 합친다). */
    private static String itemName(String name) {
        return name == null ? "" : name.strip().replaceAll("\\s+", " ");
    }

    private static List<NameCount> topNames(Map<String, Set<Long>> names) {
        return topNames(names, ETC_NAMES);
    }

    private static List<NameCount> topNames(Map<String, Set<Long>> names, int limit) {
        return names.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Set<Long>> e) -> e.getValue().size()).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(limit).map(e -> new NameCount(e.getKey(), e.getValue().size())).toList();
    }

    private static List<String> valuesOf(TrendItem.Garment g, String type) {
        return switch (type) {
            case TrendWeeklyStat.CATEGORY -> g.category() == null ? List.of() : List.of(g.category());
            case TrendItemValue.ITEM_TYPE -> g.type() == null ? List.of() : List.of(g.type());
            case TrendItemValue.MATERIAL -> g.materials();
            case TrendItemValue.COLOR -> g.colors();
            case TrendItemValue.SILHOUETTE -> g.silhouettes();
            case TrendItemValue.GENDER -> g.genders();
            case TrendItemValue.PATTERN -> g.patterns();
            case TrendItemValue.DETAIL -> g.details();
            default -> List.of();
        };
    }

    /** 주 하나의 원문 수. current는 이번 주(진행 중 — 다 차지 않았다). */
    public record WeekCount(LocalDate weekStart, int count, boolean current) {
    }

    /**
     * 주별 원문 수 — 범위(카테고리·종류·속성 조건·시즌)에서 값 하나(valueType·value, 둘 다 없으면 범위 전체)가 나온 분석 완료 원문을
     * 주(월요일 시작)마다 센다. 도구 경로의 trend_series용.
     */
    public List<WeekCount> series(QuestionInterpreter.Interpretation question, String valueType, String value, int weeks, LocalDate today) {
        Scope scope = new Scope(question.category(), question.itemType(), Set.copyOf(question.seasons()), null, question.filters(), TOP);
        LocalDate thisWeek = today.with(java.time.DayOfWeek.MONDAY);
        LocalDate from = thisWeek.minusWeeks(Math.max(1, weeks) - 1L);
        List<SourceDocument> docs = sourceDocumentRepository.findByStatusAndPublishedAtBetween(SourceDocument.DONE, from, today);
        Map<Long, TrendItem> items = trendItemRepository.findByDocumentIdIn(docs.stream().map(SourceDocument::getId).toList())
                .stream().collect(Collectors.toMap(TrendItem::getDocumentId, Function.identity()));
        Map<LocalDate, Set<Long>> byWeek = new java.util.TreeMap<>();
        for (LocalDate w = from; !w.isAfter(thisWeek); w = w.plusWeeks(1)) byWeek.put(w, new HashSet<>());
        for (SourceDocument doc : docs) {
            TrendItem item = items.get(doc.getId());
            if (item == null || !scope.inSeason(item)) continue;
            List<TrendItem.Garment> garments = item.garments().stream().filter(scope::matches).toList();
            boolean hit = value == null ? !garments.isEmpty()
                    : garments.stream().anyMatch(g -> valuesOf(g, valueType).contains(value));
            Set<Long> week = byWeek.get(TrendAggregationService.statDate(doc).with(java.time.DayOfWeek.MONDAY));
            if (hit && week != null) week.add(doc.getId());
        }
        return byWeek.entrySet().stream().map(e -> new WeekCount(e.getKey(), e.getValue().size(), e.getKey().equals(thisWeek))).toList();
    }

    /** 범위 원문 중 가장 최근 게시일(분석 완료). 범위 원문이 없으면 null. */
    private LocalDate latestDate(Scope scope) {
        List<SourceDocument> done = sourceDocumentRepository.findByStatus(SourceDocument.DONE);
        Map<Long, TrendItem> items = trendItemRepository.findByDocumentIdIn(done.stream().map(SourceDocument::getId).toList())
                .stream().collect(Collectors.toMap(TrendItem::getDocumentId, Function.identity()));
        return done.stream()
                .filter(d -> items.containsKey(d.getId()) && scope.inSeason(items.get(d.getId()))
                        && items.get(d.getId()).garments().stream().anyMatch(scope::matches))
                .map(TrendAggregationService::statDate)
                .max(Comparator.naturalOrder()).orElse(null);
    }

    private static String scopeLabel(QuestionInterpreter.Interpretation q) {
        if (q.itemTypeName() != null) {
            return (q.categoryName() == null ? "" : q.categoryName() + " · ") + q.itemTypeName();
        }
        return q.categoryName() == null ? "전체" : q.categoryName();
    }

    /** 코드값 → 이름(사용 중지 코드도 이름이 보여야 하므로 전부). */
    private Map<String, String> names(String group) {
        Map<String, String> names = new HashMap<>();
        if (group != null) {
            for (ComCd code : comCdRepository.findByCodeGroupOrderBySortOrderAsc(group)) {
                names.put(code.getCodeValue(), code.getCodeName());
            }
        }
        return names;
    }
}
