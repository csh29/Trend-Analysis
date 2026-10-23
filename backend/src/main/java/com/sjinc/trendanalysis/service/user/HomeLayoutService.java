package com.sjinc.trendanalysis.service.user;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjinc.trendanalysis.domain.User;
import com.sjinc.trendanalysis.domain.UserPreference;
import com.sjinc.trendanalysis.dto.user.HomeLayout;
import com.sjinc.trendanalysis.repository.UserPreferenceRepository;
import com.sjinc.trendanalysis.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 홈 배치 개인화(2026-10-23 사용자 결정 — 드래그앤드롭 + 사용자별 저장). 로그인한 본인 것만 읽고 쓴다(대상은 Principal).
 * 화면이 보낸 값이라 패널 이름·넓이를 여기서 거른다. 저장한 적이 없으면 기본 배치를 준다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HomeLayoutService {

    static final String KEY = "home-layout";
    /** 홈 패널과 기본 넓이(기본 배치 순서). 패널을 더하면 home.html의 data-panel과 같이 더한다 — 저장된 배치 끝에 기본값으로 붙는다. */
    static final Map<String, String> PANELS = defaults();
    static final String CHAT = "chat";

    private final UserPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    private static Map<String, String> defaults() {
        Map<String, String> panels = new LinkedHashMap<>();
        panels.put("trend", HomeLayout.HALF);
        panels.put("analysis", HomeLayout.HALF);
        panels.put(CHAT, HomeLayout.FULL);
        return panels;
    }

    public static HomeLayout defaultLayout() {
        return new HomeLayout(PANELS.entrySet().stream()
                .map(e -> new HomeLayout.Panel(e.getKey(), e.getValue(), false)).toList(), false, true);
    }

    @Transactional(readOnly = true)
    public HomeLayout get(String username) {
        return preferenceRepository.findByUserUsernameAndPrefKey(username, KEY)
                .map(pref -> read(pref.getPrefValue()))
                .orElseGet(HomeLayoutService::defaultLayout);
    }

    /** 홈 템플릿의 data-layout 값(JSON). */
    public String getJson(String username) {
        return write(get(username));
    }

    @Transactional
    public HomeLayout save(String username, HomeLayout layout) {
        HomeLayout normalized = validate(layout);
        String value = write(normalized);
        preferenceRepository.findByUserUsernameAndPrefKey(username, KEY).ifPresentOrElse(
                pref -> pref.change(value),
                () -> {
                    User user = userRepository.findByUsername(username)
                            .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자입니다: " + username));
                    preferenceRepository.save(UserPreference.builder().user(user).prefKey(KEY).prefValue(value)
                            .updatedAt(LocalDateTime.now()).build());
                });
        return normalized;
    }

    /** "기본 배치로" — 저장한 배치를 지운다. */
    @Transactional
    public HomeLayout reset(String username) {
        preferenceRepository.findByUserUsernameAndPrefKey(username, KEY).ifPresent(preferenceRepository::delete);
        return defaultLayout();
    }

    /**
     * 저장할 배치를 거른다. 모르는 패널·같은 패널 두 번·넓이 값 오류·챗봇 숨김은 400(챗봇은 홈의 핵심이라 숨기지 않는다).
     * 빠진 패널은 기본값으로 끝에 붙인다(새 패널이 생겨도 예전 화면이 보낸 배치가 깨지지 않게).
     */
    static HomeLayout validate(HomeLayout layout) {
        if (layout == null || layout.panels() == null) {
            throw new IllegalArgumentException("홈 배치가 비어 있습니다.");
        }
        if (layout.panels().size() > PANELS.size()) {
            throw new IllegalArgumentException("홈 패널이 너무 많습니다.");
        }
        List<HomeLayout.Panel> panels = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (HomeLayout.Panel p : layout.panels()) {
            if (p == null || !PANELS.containsKey(p.id())) {
                throw new IllegalArgumentException("알 수 없는 홈 패널입니다: " + (p == null ? null : p.id()));
            }
            if (!seen.add(p.id())) {
                throw new IllegalArgumentException("같은 홈 패널이 두 번 있습니다: " + p.id());
            }
            if (!HomeLayout.HALF.equals(p.width()) && !HomeLayout.FULL.equals(p.width())) {
                throw new IllegalArgumentException("패널 넓이는 half 또는 full입니다: " + p.width());
            }
            if (CHAT.equals(p.id()) && p.hidden()) {
                throw new IllegalArgumentException("트렌드 챗봇은 숨길 수 없습니다.");
            }
            panels.add(p);
        }
        PANELS.forEach((id, width) -> {
            if (!seen.contains(id)) {
                panels.add(new HomeLayout.Panel(id, width, false));
            }
        });
        return new HomeLayout(List.copyOf(panels), layout.summaryCollapsed(), layout.autoRefresh());
    }

    /** 저장된 값을 읽는다 — 그 사이 패널이 바뀌어 맞지 않으면(모르는 패널 등) 버리고 기본 배치를 준다. 읽기는 실패시키지 않는다. */
    private HomeLayout read(String value) {
        try {
            return validate(objectMapper.readValue(value, HomeLayout.class));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("저장된 홈 배치를 읽지 못해 기본 배치를 씁니다: {}", e.getMessage());
            return defaultLayout();
        }
    }

    private String write(HomeLayout layout) {
        try {
            return objectMapper.writeValueAsString(layout);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("홈 배치를 JSON으로 만들지 못했습니다.", e);
        }
    }
}
