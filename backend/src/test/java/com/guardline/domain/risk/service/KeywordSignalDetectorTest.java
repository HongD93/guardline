package com.guardline.domain.risk.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.guardline.domain.risk.client.SignalDetectionResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 규칙이 잡아야 할 것과 잡으면 안 되는 것을 고정한다.
 * 규칙 오탐은 등급을 뒤집을 수 있으므로 반증 케이스를 함께 둔다.
 */
class KeywordSignalDetectorTest {

    private final KeywordSignalDetector detector = new KeywordSignalDetector();

    private List<String> stageIds(List<String> lines) {
        return detector.detect(lines).stages().stream().map(SignalDetectionResult.Detected::id).toList();
    }

    private List<String> negativeIds(List<String> lines) {
        return detector.detect(lines).negatives().stream().map(SignalDetectionResult.Detected::id).toList();
    }

    @Test
    @DisplayName("발설 금지는 격리 유도로 잡는다")
    void 발설금지는_격리유도다() {
        List<String> lines = List.of(
                "그리고 이건 수사기밀이라서 가족분이나 은행 직원한테도 절대 말씀하시면 안 됩니다");

        assertThat(stageIds(lines)).contains("S3");
    }

    @Test
    @DisplayName("통화 유지 강요는 격리 유도로 잡는다")
    void 통화유지_강요는_격리유도다() {
        assertThat(stageIds(List.of("우선 이 통화는 절대 끊지 마세요"))).contains("S3");
    }

    @Test
    @DisplayName("조용한 곳으로 이동 지시는 격리 유도로 잡는다")
    void 이동지시는_격리유도다() {
        assertThat(stageIds(List.of("지금 조용한 곳으로 이동해주세요"))).contains("S3");
    }

    @Test
    @DisplayName("규칙이 잡은 격리 유도는 단독 트리거 조건을 넘긴다")
    void 규칙_신뢰도는_격리floor를_넘긴다() {
        SignalDetectionResult result = detector.detect(List.of("발설하시면 수사 방해로 추가 입건됩니다"));

        assertThat(result.stages()).singleElement()
                .satisfies(signal -> assertThat(signal.confidence()).isGreaterThanOrEqualTo(0.8));
    }

    @Test
    @DisplayName("가족과 상의 권유는 감점 신호로 잡는다")
    void 가족상의_권유는_감점이다() {
        List<String> lines = List.of("안내문을 문자로 보내드릴 테니 가족분들과 상의해 보시고 편하실 때 연락 주세요");

        assertThat(negativeIds(lines)).contains("N1");
    }

    @Test
    @DisplayName("천천히 확인하라는 권유는 감점 신호로 잡는다")
    void 천천히_확인_권유는_감점이다() {
        assertThat(negativeIds(List.of("천천히 확인해 보시고 진행하세요"))).contains("N1");
    }

    @Test
    @DisplayName("공식 앱·대표번호 안내는 감점 신호로 잡는다")
    void 공식채널_재확인_유도는_감점이다() {
        List<String> lines = List.of(
                "재발급은 고객님께서 직접 오오카드 앱에서 신청하시거나 카드 뒷면에 있는 대표번호로 연락주시면 됩니다");

        assertThat(negativeIds(lines)).contains("N2");
    }

    @Test
    @DisplayName("비밀번호를 묻지 않는다는 안내는 감점 신호로 잡는다")
    void 비밀번호_미요구_안내는_감점이다() {
        List<String> lines = List.of("저희가 이 통화에서 카드번호나 비밀번호를 여쭤보는 일은 절대 없습니다");

        assertThat(negativeIds(lines)).contains("N2");
    }

    @Test
    @DisplayName("사기 통화의 협박 문구를 감점 신호로 잘못 잡지 않는다")
    void 사기_협박문구는_감점이_아니다() {
        List<String> lines = List.of(
                "홍길동 씨 명의로 개설된 대포통장이 자금세탁 사건에 사용된 정황이 확인됐습니다",
                "확인 절차 없이는 공범으로 간주됩니다",
                "내일 오전에 체포영장이 청구됩니다",
                "지금 알려드리는 국가안전계좌로 전액 이체해 주시면");

        assertThat(negativeIds(lines)).isEmpty();
    }

    @Test
    @DisplayName("정상 카드사 통화는 기관 소개(S1)만 잡고 위험 단계는 잡지 않는다")
    void 정상통화는_S1까지만_잡는다() {
        List<String> lines = List.of(
                "안녕하세요 고객님 오오카드 이상거래탐지팀 김서연입니다",
                "해당 건은 승인 거절 처리되었고 카드는 현재 일시 정지 상태입니다",
                "지금 추가로 진행하실 부분은 없습니다");

        assertThat(stageIds(lines)).containsExactly("S1");
        assertThat(stageIds(lines)).doesNotContain("S3");
    }

    @Test
    @DisplayName("기관 사칭과 공포 조성을 규칙으로 잡는다")
    void 기관사칭과_공포조성을_잡는다() {
        List<String> lines = List.of(
                "서울중앙지검 첨단범죄수사부 김민수 수사관입니다",
                "홍길동 씨 명의로 개설된 대포통장이 자금세탁 사건에 사용된 정황이 확인됐습니다",
                "현재 피의자 신분으로 조사 대상에 올라 있습니다");

        assertThat(stageIds(lines)).contains("S1", "S2");
    }

    @Test
    @DisplayName("가족 간 송금 대화를 격리 유도로 잘못 잡지 않는다")
    void 가족송금_대화는_격리유도가_아니다() {
        List<String> lines = List.of(
                "이번 달에 관리비랑 보험료가 같이 나가서 좀 모자라네 오십만 원만 먼저 보내줄 수 있어",
                "어 지금 보낼게 엄마 국민은행 계좌 맞지",
                "됐어 천천히 줘");

        assertThat(stageIds(lines)).isEmpty();
    }
}
