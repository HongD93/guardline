package com.guardline.domain.call.service;

import com.guardline.domain.call.response.QuotaResponseDTO;
import com.guardline.global.config.QuotaProperties;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 통화 세션 수를 제한한다. 상태가 작고 갱신이 잦아 인스턴스 락으로 직렬화한다.
 *
 * <p>인스턴스가 하나뿐인 배포를 전제로 메모리에 들고 있다. 여러 인스턴스로 늘리는 시점에는
 * 공유 저장소가 필요해지므로 그때 다시 설계한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionQuota {

    private final QuotaProperties properties;

    private LocalDate today = LocalDate.now();
    private int usedToday = 0;
    private int active = 0;

    /**
     * 세션 하나를 확보한다.
     *
     * @return 거절 사유. 확보에 성공하면 null이다.
     */
    public synchronized String acquire() {
        rollOverIfNewDay();

        if (active >= properties.concurrent()) {
            return "지금 다른 사용자가 체험 중입니다. 잠시 후 다시 시도해 주세요. (동시 %d개 제한)"
                    .formatted(properties.concurrent());
        }
        if (usedToday >= properties.dailySessions()) {
            return "오늘의 체험 횟수(%d회)를 모두 사용했습니다. API 크레딧 보호를 위한 제한이며 자정에 초기화됩니다."
                    .formatted(properties.dailySessions());
        }

        active += 1;
        usedToday += 1;
        log.info("세션 확보: 동시 {}/{}, 오늘 {}/{}",
                active, properties.concurrent(), usedToday, properties.dailySessions());
        return null;
    }

    public synchronized void release() {
        if (active > 0) {
            active -= 1;
        }
    }

    public synchronized QuotaResponseDTO snapshot() {
        rollOverIfNewDay();
        return new QuotaResponseDTO(
                Math.max(0, properties.dailySessions() - usedToday),
                properties.dailySessions(),
                properties.maxDurationMs() / 1000);
    }

    public long maxDurationMs() {
        return properties.maxDurationMs();
    }

    /** 자정을 넘겼으면 일일 사용량을 초기화한다. 확보·조회 시점에 확인한다. */
    private void rollOverIfNewDay() {
        LocalDate now = LocalDate.now();
        if (!now.equals(today)) {
            today = now;
            usedToday = 0;
            log.info("일일 세션 한도 초기화: {}", now);
        }
    }
}
