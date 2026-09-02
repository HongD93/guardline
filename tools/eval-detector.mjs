// 판정 엔진 평가 하네스.
//
// 시나리오 7종의 대사를 그대로 감지기에 넣고 기대 등급과 대조한다. 오디오를 태우지 않으므로
// 프롬프트를 고칠 때마다 몇 분 안에 전수 검증을 돌릴 수 있다. 실제 STT 결과는 거의 원문과
// 같으므로(1주차 검증) 프롬프트 반복에는 이 방식으로 충분하다.
//
// 이 하네스는 LLM 감지만 측정한다. 실제 시스템은 여기에 규칙 기반 감지(KeywordSignalDetector)를
// 합집합으로 얹으므로 항상 이 결과보다 좋다. 즉 여기 수치는 하한선이다.
//
//   node tools/eval-detector.mjs            전체
//   node tools/eval-detector.mjs F1         id에 F1이 포함된 것만
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { score } from './score.mjs';

const ROOT = path.resolve(import.meta.dirname, '..');
const LOCAL = path.join(ROOT, 'backend', 'application-local.yaml');
const ENDPOINT = 'https://llm-gateway.assemblyai.com/v1/chat/completions';

const KEY = readFileSync(LOCAL, 'utf8').match(/api-key:\s*(\S+)/)[1];
const MODEL = process.env.GUARDLINE_LLM_MODEL ?? 'qwen3.5-4b-32k-fast';

/** 레이트 리밋(429)이 매우 빡빡하다. 평가는 느려도 되니 넉넉히 기다리고 재시도한다. */
const CALL_GAP_MS = 6000;
const RETRY_WAIT_MS = 25000;
const MAX_RETRIES = 6;
const MIN_CONFIDENCE = 0.6;

// 백엔드와 프롬프트가 갈라지면 평가가 의미 없어지므로 Java 원본에서 직접 읽는다.
const SYSTEM_PROMPT = readFileSync(
  path.join(ROOT, 'backend/src/main/java/com/guardline/domain/risk/client/LlmGatewayClient.java'),
  'utf8',
).match(/SYSTEM_PROMPT = """\n([\s\S]*?)\n {12}""";/)[1].replace(/^ {12}/gm, '');

const LINE = /^(S[1-5]|N[124])\|(yes|no)\|([\d.]+)\|(.*)$/;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function call(transcript) {
  const response = await fetch(ENDPOINT, {
    method: 'POST',
    headers: { authorization: KEY, 'content-type': 'application/json' },
    body: JSON.stringify({
      model: MODEL,
      max_tokens: 1024,
      temperature: 0,
      messages: [
        { role: 'system', content: SYSTEM_PROMPT },
        { role: 'user', content: `[통화 내용]\n${transcript.join('\n')}` },
      ],
    }),
  });
  return { status: response.status, json: await response.json() };
}

async function detect(transcript) {
  let last = 'unknown';

  for (let attempt = 0; attempt <= MAX_RETRIES; attempt += 1) {
    const { status, json } = await call(transcript);

    if (json.choices) {
      const stages = {};
      const negatives = new Set(); // 같은 감점 신호를 여러 줄로 뱉는 경우가 있다
      for (const raw of json.choices[0].message.content.split('\n')) {
        const m = raw.trim().match(LINE);
        if (!m || m[2] !== 'yes') continue;

        const confidence = Math.min(1, Math.max(0, parseFloat(m[3]) || 0));
        if (confidence < MIN_CONFIDENCE) continue;

        if (m[1].startsWith('S')) stages[m[1]] = confidence;
        else negatives.add(m[1]);
      }
      return { stages, negatives: [...negatives] };
    }

    last = json.metadata?.errors?.[0] ?? json.message ?? 'unknown';
    if (status !== 429 || attempt === MAX_RETRIES) {
      return { error: last };
    }
    process.stdout.write(`    (429 - ${RETRY_WAIT_MS / 1000}초 대기 후 재시도 ${attempt + 1}/${MAX_RETRIES})\n`);
    await sleep(RETRY_WAIT_MS);
  }
  return { error: last };
}

const spec = JSON.parse(readFileSync(path.join(ROOT, 'scenarios', 'scenarios.json'), 'utf8'));
const filter = process.argv[2];
const targets = spec.scenarios.filter((s) => !filter || s.id.includes(filter));

console.log(`모델: ${MODEL} / 시나리오 ${targets.length}종\n`);
console.log('시나리오                 기대      실제      점수   감지된 신호');
console.log('─'.repeat(78));

let passed = 0;
const failures = [];

for (const [index, sc] of targets.entries()) {
  if (index > 0) await sleep(CALL_GAP_MS);

  const transcript = sc.turns.map((t) => t.text);
  const detected = await detect(transcript);

  if (detected.error) {
    console.log(`${sc.id.padEnd(22)} ${sc.expected.level.padEnd(9)} 호출실패   -      ${detected.error}`);
    failures.push({ id: sc.id, reason: detected.error });
    continue;
  }

  const result = score(detected.stages, detected.negatives, sc.inbound);
  const ok = result.level === sc.expected.level;
  if (ok) passed += 1;
  else failures.push({ id: sc.id, expected: sc.expected.level, actual: result.level, detected });

  const signals = [
    ...Object.entries(detected.stages).map(([id, c]) => `${id}(${c})`),
    ...detected.negatives,
  ].join(' ') || '없음';

  console.log(
    `${sc.id.padEnd(22)} ${sc.expected.level.padEnd(9)} ${result.level.padEnd(9)} ` +
      `${String(result.score).padStart(5)}  ${signals}${ok ? '' : '   ← 불일치'}`,
  );
}

console.log('─'.repeat(78));
console.log(`통과 ${passed}/${targets.length}`);

if (failures.length) {
  console.log('\n실패 상세:');
  for (const f of failures) {
    console.log(`  ${f.id}: ${f.reason ?? `기대 ${f.expected} → 실제 ${f.actual}`}`);
  }
  process.exitCode = 1;
}
