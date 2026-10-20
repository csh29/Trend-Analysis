package com.sjinc.trendanalysis.dto.program;

import com.sjinc.trendanalysis.domain.Program;

import java.util.List;

/**
 * 사이드바의 2뎁스 메뉴 그룹 하나(공통코드 MENU_GROUP의 코드 하나). id는 엘리먼트 id이자 접힘 상태를 기억하는 키,
 * defaultOpen이면 접힘 상태를 기억한 적 없는 첫 방문에 펼쳐 둔다.
 */
public record MenuGroup(String id, String name, String icon, boolean defaultOpen, List<Program> programs) {
}
