package com.sjinc.trendanalysis.service.analysis;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 원문 URL 정규화 — 같은 기사·게시물이 다시 들어오면(크롤링 재수집, 공유 링크) 같은 URL로 보이게 만든다. 같은 원문이 두 번 저장되면
 * 유행 집계에서 두 번 세진다(2026-10-22 사용자 결정: 같은 원문은 URL로 판별). 같은 매체가 다른 게시물로 여러 번 올린 것은
 * URL이 달라 여기서 막지 않는다 — 그건 집계에서 매체 수로 따로 본다.
 */
public final class SourceUrls {

    /** 공유·광고 추적용이라 원문을 가르지 않는 쿼리 파라미터. utm_*는 접두사로 거른다. */
    static final Set<String> TRACKING_PARAMS = Set.of("fbclid", "gclid", "igshid", "igsh", "si", "ref", "ref_src",
            "spm", "mc_cid", "mc_eid", "_ga", "from", "share");

    private SourceUrls() {
    }

    /**
     * http(s) URL이면 scheme·host 소문자, 앞의 www. 제거, 기본 포트·#조각·추적 파라미터 제거, 남은 파라미터 정렬, 끝의 / 제거.
     * URL이 아니거나 읽을 수 없으면 앞뒤 공백만 뗀 값. 비었으면 null.
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return value;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
            return value;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        int port = uri.getPort();
        boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.equals("/")) {
            path = "";
        }
        String query = uri.getRawQuery() == null ? "" : Arrays.stream(uri.getRawQuery().split("&"))
                .filter(p -> !p.isBlank())
                .filter(p -> {
                    String name = p.split("=", 2)[0].toLowerCase(Locale.ROOT);
                    return !name.startsWith("utm_") && !TRACKING_PARAMS.contains(name);
                })
                .sorted()
                .collect(Collectors.joining("&"));
        // https와 http는 같은 원문으로 본다(대부분 http가 https로 넘어간다)
        return "https://" + host + (defaultPort ? "" : ":" + port) + path + (query.isEmpty() ? "" : "?" + query);
    }
}
