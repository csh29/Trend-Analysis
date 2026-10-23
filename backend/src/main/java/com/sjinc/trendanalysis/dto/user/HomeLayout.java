package com.sjinc.trendanalysis.dto.user;

import java.util.List;

/**
 * 홈 배치(사용자별). panels 순서대로 그리고, 넓이는 half(한 줄에 둘)·full(한 줄 전체), hidden이면 그리지 않는다.
 * summaryCollapsed는 요약 접기, autoRefresh는 자동 새로고침 켜기.
 */
public record HomeLayout(List<Panel> panels, boolean summaryCollapsed, boolean autoRefresh) {

    public static final String HALF = "half";
    public static final String FULL = "full";

    public record Panel(String id, String width, boolean hidden) {
    }
}
