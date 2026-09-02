package com.guardline.domain.risk;

/** 위험도 등급. 임계치는 룰셋 설계(analytics/report/guardline)에서 정한 값이다. */
public enum RiskLevel {

    SAFE("안전", 0),
    CAUTION("주의", 30),
    WARNING("경고", 55),
    DANGER("위험", 80);

    private final String label;
    private final int threshold;

    RiskLevel(String label, int threshold) {
        this.label = label;
        this.threshold = threshold;
    }

    public String getLabel() {
        return label;
    }

    /** 표시 점수와 등급이 어긋나지 않도록 반올림된 값으로 판정한다. */
    public static RiskLevel of(double roundedScore) {
        RiskLevel matched = SAFE;
        for (RiskLevel level : values()) {
            if (roundedScore >= level.threshold) {
                matched = level;
            }
        }
        return matched;
    }
}
