package com.sjinc.trendanalysis.service.analysis;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 정형화 프롬프트 캐시를 먼저 데우는 관문. 분석 스레드 2개가 여러 건을 동시에 보내면 캐시가 아직 없는 상태에서 둘 다 캐시를 새로
 * 만든다(캐시는 첫 응답이 나와야 쓸 수 있다 — 2026-10-28 텍스트 여러 건 등록 때 두 건 모두 "캐시 생성 8874·읽기 0"). 그래서 캐시가
 * 식은 프롬프트 종류(key)는 한 건만 먼저 보내고, 나머지는 그 건이 끝날 때까지 기다렸다가 캐시를 읽게 한다.
 *
 * <p>key는 시스템 프롬프트가 같은 묶음(모델 + 자료 유형 이미지만·본문만·본문+이미지) — 자료 유형마다 붙는 규칙이 달라 캐시가 따로다.
 * 캐시는 마지막으로 쓴 뒤 5분 유지되므로 4분 안에 성공한 호출이 있으면 따뜻한 것으로 본다. 데우는 건이 실패하면 기다리던 건 중
 * 하나가 이어 데운다. 데우는 건이 3분을 넘기면(읽기 타임아웃) 더 기다리지 않고 보낸다.
 */
class CacheWarmGate {

    static final Duration WARM_FOR = Duration.ofMinutes(4);
    static final Duration MAX_WAIT = Duration.ofMinutes(3);

    private final Map<String, State> states = new HashMap<>();
    private final LongSupplier nanoTime;

    private static final class State {
        Long warmedAt; // 마지막으로 성공한 호출이 끝난 시각(nanoTime)
        boolean warming;
    }

    CacheWarmGate() {
        this(System::nanoTime);
    }

    CacheWarmGate(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    /**
     * 보내도 되면 돌아온다. 캐시가 따뜻하면 바로(false), 아무도 데우지 않으면 이 건이 데우는 쪽(true), 누가 데우는 중이면 끝날 때까지
     * 기다린다. true를 받았으면 호출이 끝난 뒤 반드시 {@link #leave}를 부른다.
     */
    synchronized boolean enter(String key) throws InterruptedException {
        State state = states.computeIfAbsent(key, k -> new State());
        long deadline = nanoTime.getAsLong() + MAX_WAIT.toNanos();
        while (true) {
            if (isWarm(state)) return false;
            if (!state.warming) {
                state.warming = true;
                return true;
            }
            long left = deadline - nanoTime.getAsLong();
            if (left <= 0) return false; // 데우는 건이 너무 오래 걸린다 — 캐시 없이 보낸다
            wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
        }
    }

    /** 호출이 끝났다. 성공한 호출은 캐시를 썼거나 만들었으니 따뜻한 시각을 새로 한다. 데우는 건이었으면 기다리는 건을 깨운다. */
    synchronized void leave(String key, boolean warmer, boolean succeeded) {
        State state = states.computeIfAbsent(key, k -> new State());
        if (succeeded) state.warmedAt = nanoTime.getAsLong();
        if (warmer) {
            state.warming = false;
            notifyAll();
        }
    }

    private boolean isWarm(State state) {
        return state.warmedAt != null && nanoTime.getAsLong() - state.warmedAt < WARM_FOR.toNanos();
    }

    /** 시스템 프롬프트가 같은 묶음 — structurer.system_prompt가 본문·이미지 유무로 붙이는 규칙 파일이 달라진다. */
    static String keyOf(AiClient.StructureRequest request) {
        boolean text = request.text() != null && !request.text().isBlank();
        boolean images = request.images() != null && !request.images().isEmpty();
        return request.model() + ":" + (text && images ? "ARTICLE" : images ? "IMAGE" : "TEXT");
    }
}
