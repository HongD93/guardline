// 시나리오 대사를 edge-tts로 음성 파일로 굽고, 라벨이 붙은 manifest를 만든다.
//   node tools/gen-audio.mjs            전체 생성
//   node tools/gen-audio.mjs F1         id에 F1이 포함된 시나리오만
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

const execFileAsync = promisify(execFile);
const ROOT = path.resolve(import.meta.dirname, '..');
const OUT = path.join(ROOT, 'scenarios', 'audio');

// 같은 성별 화자가 겹치는 구간이 있어 역할별로 속도·피치를 벌려둔다.
const STYLE = {
  fraudster: { rate: '-8%', pitch: '-10Hz' }, // 권위적인 톤
  agent: { rate: '+0%', pitch: '+0Hz' },
  family: { rate: '-5%', pitch: '+15Hz' },
  seller: { rate: '+5%', pitch: '+10Hz' },
  user: { rate: '+0%', pitch: '+0Hz' },
};

const filter = process.argv[2];
const spec = JSON.parse(await readFile(path.join(ROOT, 'scenarios', 'scenarios.json'), 'utf8'));
const targets = spec.scenarios.filter((s) => !filter || s.id.includes(filter));

if (targets.length === 0) {
  console.error(`매칭되는 시나리오가 없습니다: ${filter}`);
  process.exit(1);
}

const manifest = [];

for (const sc of targets) {
  const dir = path.join(OUT, sc.id);
  await mkdir(dir, { recursive: true });
  console.log(`\n[${sc.id}] ${sc.title} — ${sc.turns.length}턴`);

  for (const [i, turn] of sc.turns.entries()) {
    const seq = String(i + 1).padStart(2, '0');
    const voice = spec.voices[turn.speaker];
    if (!voice) throw new Error(`${sc.id} 턴 ${seq}: 알 수 없는 화자 "${turn.speaker}"`);

    const style = STYLE[turn.speaker] ?? { rate: '+0%', pitch: '+0Hz' };
    const file = `${seq}_${turn.speaker}.mp3`;
    const abs = path.join(dir, file);

    await execFileAsync('edge-tts', [
      '--voice', voice,
      '--rate', style.rate,
      '--pitch', style.pitch,
      '--text', turn.text,
      '--write-media', abs,
    ]);

    manifest.push({
      scenario: sc.id,
      kind: sc.kind,
      seq: i + 1,
      speaker: turn.speaker,
      audio: path.relative(ROOT, abs).replaceAll('\\', '/'),
      text: turn.text,
      labels: turn.labels,
      synthetic: true, // 합성 음성. 실제 통화 녹취가 아님
    });

    process.stdout.write(`  ${seq} ${turn.speaker.padEnd(9)} ${turn.labels.join(',') || '-'}\n`);
  }
}

// 일부만 재생성해도 나머지 시나리오의 매핑이 사라지면 안 된다. 기존 manifest에서 이번에
// 다시 만든 시나리오만 걷어내고 병합한다. 통째로 덮어쓰면 프론트가 나머지 오디오를 못 찾는다.
const manifestPath = path.join(ROOT, 'scenarios', 'manifest.json');
const regenerated = new Set(targets.map((s) => s.id));
let previous = [];
try {
  previous = JSON.parse(await readFile(manifestPath, 'utf8'));
} catch {
  previous = [];
}
const merged = [...previous.filter((entry) => !regenerated.has(entry.scenario)), ...manifest]
  .sort((a, b) => a.scenario.localeCompare(b.scenario) || a.seq - b.seq);

await writeFile(manifestPath, JSON.stringify(merged, null, 2) + '\n', 'utf8');

const labelled = manifest.filter((m) => m.labels.length > 0).length;
console.log(`\n음성 ${manifest.length}개 생성. 라벨이 붙은 발화 ${labelled}개.`);
console.log(`manifest: ${path.relative(ROOT, manifestPath)} (전체 ${merged.length}개 발화)`);
