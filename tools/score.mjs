// 룰셋 스코어러 참조 구현 + 시나리오 검증.
// LLM은 신호 감지만 하고 점수는 이 결정적 함수가 계산한다.
//   node tools/score.mjs          전체 시나리오 채점
//   node tools/score.mjs --trace  사기 시나리오의 턴별 위험도 추이까지 출력
import { readFile } from 'node:fs/promises';
import path from 'node:path';

const ROOT = path.resolve(import.meta.dirname, '..');

export const STAGE_W = { S1: 15, S2: 20, S3: 30, S4: 25, S5: 40 };
export const NEG_W = { N1: 40, N2: 30, N3: 25, N4: 15 };
const CHAIN = [1.0, 1.0, 1.15, 1.35, 1.6, 1.9]; // 인덱스 = 최장 연속 체인 길이
const ORDER = ['S1', 'S2', 'S3', 'S4', 'S5'];

/** 순서대로 밟힌 단계의 최장 연속 길이. S1→S2→S3 = 3, S1+S5 = 1 */
function longestChain(stages) {
  let best = 0;
  let run = 0;
  for (const id of ORDER) {
    if (stages[id] > 0) {
      run += 1;
      best = Math.max(best, run);
    } else {
      run = 0;
    }
  }
  return best;
}

export function level(score) {
  if (score >= 80) return '위험';
  if (score >= 55) return '경고';
  if (score >= 30) return '주의';
  return '안전';
}

/**
 * @param stages    { S1: 0.9, S2: 0.7 } 형태의 신뢰도 맵
 * @param negatives ['N1', 'N2'] 중복은 무시
 * @param inbound   false면 사용자가 건 전화 → N3 자동 적용
 */
export function score(stages, negatives = [], inbound = true) {
  const raw = ORDER.reduce((sum, id) => sum + STAGE_W[id] * (stages[id] ?? 0), 0);

  const chain = longestChain(stages);
  const chained = raw * CHAIN[chain];

  const negs = new Set(negatives);
  if (!inbound) negs.add('N3');
  const penalty = [...negs].reduce((sum, id) => sum + (NEG_W[id] ?? 0), 0);

  let value = Math.min(100, Math.max(0, chained - penalty));

  // 격리 유도 단독 트리거. 정상 기관은 어떤 경우에도 정보 차단을 요구하지 않는다.
  // floor를 위험 임계치(80)에 맞춘 이유: 격리가 확인된 시점에 개입해야 앱 설치(S4)와
  // 이체(S5) 이전에 끊을 수 있다. S4까지 기다리면 이미 원격제어 앱이 깔린 뒤다.
  const isolation = (stages.S3 ?? 0) >= 0.8;
  if (isolation) value = Math.max(value, 80);

  // 표시 점수와 판정 등급이 어긋나지 않도록 반올림된 값으로 등급을 매긴다.
  const rounded = Math.round(value * 10) / 10;

  return {
    score: rounded,
    level: level(rounded),
    raw: Math.round(raw * 10) / 10,
    chain,
    multiplier: CHAIN[chain],
    penalty,
    isolationFloor: isolation,
  };
}

// ---- 시나리오 검증 ----
const spec = JSON.parse(await readFile(path.join(ROOT, 'scenarios', 'scenarios.json'), 'utf8'));
const trace = process.argv.includes('--trace');

console.log('시나리오               종류    raw   체인  배수   감점   점수   판정');
console.log('─'.repeat(72));

for (const sc of spec.scenarios) {
  const r = score(sc.expected.stages, sc.expected.negatives, sc.inbound);
  const ok = r.level === sc.expected.level;
  console.log(
    `${sc.id.padEnd(22)} ${sc.kind.padEnd(7)} ${String(r.raw).padStart(5)} ` +
      `${String(r.chain).padStart(4)} ${String(r.multiplier).padStart(5)} ` +
      `${String(r.penalty).padStart(6)} ${String(r.score).padStart(6)}   ` +
      `${r.level}${r.isolationFloor ? ' (격리floor)' : ''} ${ok ? '' : '  ← 기대와 불일치'}`,
  );
}

if (trace) {
  const fraud = spec.scenarios.find((s) => s.kind === 'fraud');
  console.log(`\n[${fraud.id}] 턴별 위험도 추이 — 데모 타임라인\n`);
  console.log('턴  화자        신호        점수   판정');
  console.log('─'.repeat(52));

  const seen = {};
  for (const [i, turn] of fraud.turns.entries()) {
    for (const l of turn.labels) {
      if (STAGE_W[l]) seen[l] = fraud.expected.stages[l];
    }
    const r = score(seen, [], fraud.inbound);
    console.log(
      `${String(i + 1).padStart(2)}  ${turn.speaker.padEnd(10)} ` +
        `${(turn.labels.join(',') || '-').padEnd(10)} ${String(r.score).padStart(6)}   ${r.level}`,
    );
  }
}
