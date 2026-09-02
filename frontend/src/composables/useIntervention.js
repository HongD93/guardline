import { ref, watch } from 'vue';

/** 이 등급에 도달하면 개입한다. 룰셋의 위험 임계치와 같다. */
const DANGER_LEVEL = '위험';

/** 경고 문구. 짧아야 통화 중에 들린다. */
const DANGER_SPEECH = '보이스피싱이 의심됩니다. 지금 통화를 끊고 가족에게 확인하세요.';

/**
 * 위험 등급 도달 시 개입한다.
 *
 * <p>TTS는 브라우저 내장 Web Speech API를 쓴다. 외부 TTS API는 키와 지연을 추가하는데
 * 경고 한 줄을 읽는 데 그럴 이유가 없다.
 *
 * <p>보호자 알림은 화면 카드로 시뮬레이션한다. 실제 발송은 통신사 연동이 필요해 이 프로젝트
 * 범위 밖이다(로드맵).
 */
export function useIntervention(risk) {
  // 1. State
  const spoken = ref(false);
  const guardianNotified = ref(false);
  const notifiedAt = ref(null);
  const speechBlocked = ref(false);

  // 2. Getters
  // (없음 - 표시용 파생값은 컴포넌트에서 계산한다)

  // 3. Actions
  const speak = (text) => {
    if (!('speechSynthesis' in window)) {
      speechBlocked.value = true;
      return;
    }
    try {
      const utterance = new SpeechSynthesisUtterance(text);
      utterance.lang = 'ko-KR';
      utterance.rate = 1.05; // 통화에 끼어드는 경고라 약간 빠르게
      window.speechSynthesis.cancel(); // 이전 경고가 남아 있으면 덮어쓴다
      window.speechSynthesis.speak(utterance);
    } catch {
      speechBlocked.value = true;
    }
  };

  const reset = () => {
    spoken.value = false;
    guardianNotified.value = false;
    notifiedAt.value = null;
    speechBlocked.value = false;
    if ('speechSynthesis' in window) {
      window.speechSynthesis.cancel();
    }
  };

  // 위험 등급에 처음 도달한 순간에만 개입한다. 판정은 10초마다 도는데 매번 말하면
  // 통화 내용을 덮어버린다.
  watch(
    () => risk.value?.level,
    (level) => {
      if (level !== DANGER_LEVEL || spoken.value) {
        return;
      }
      spoken.value = true;
      guardianNotified.value = true;
      notifiedAt.value = new Date();
      speak(DANGER_SPEECH);
    },
  );

  /** 데모에서 경고를 놓쳤을 때 다시 들려준다. */
  const replay = () => speak(DANGER_SPEECH);

  return { spoken, guardianNotified, notifiedAt, speechBlocked, reset, replay };
}
