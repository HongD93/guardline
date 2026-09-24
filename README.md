# Guard Line

Korean voice phishing risk signals, shown with the words that triggered them.

[한국어](README.ko.md)

Guard Line is a browser prototype for the AssemblyAI Voice Agent Hackathon. It streams synthetic Korean call audio or microphone input to AssemblyAI, analyzes the transcript, and displays risk signals with quoted evidence. At the danger level it shows an intervention panel and attempts a spoken warning using the browser's speech synthesis.

The guardian notification is a simulation. The app does not send a message, terminate a real telephone call, or verify the caller's identity. The risk score is a heuristic, not a probability of fraud.

## Try it locally

Requirements: Java 21+, Node.js 22.12+ or 24, and an AssemblyAI account with access to the configured streaming and LLM models. The Gradle wrapper is included.

1. Create `backend/application-local.yaml` on your machine:

```yaml
guardline:
  assemblyai:
    api-key: YOUR_ASSEMBLYAI_API_KEY
```

This file is ignored by Git and Docker. Never put a key in frontend code. Alternatively set the `ASSEMBLYAI_API_KEY` environment variable.

2. From `backend`, run `./gradlew bootRun` (Windows: `.\gradlew.bat bootRun`).
3. From `frontend`, run `npm ci` and `npm run dev`.
4. Open `http://localhost:5175`. Select a synthetic scenario and click `시나리오 재생` (Play scenario). Wait for `종료됨` (Closed); `마지막 판정 중` means the final assessment is still pending.

Scenario playback sends the included synthetic audio to AssemblyAI and consumes API usage. Microphone input also leaves the device. Use synthetic samples for evaluation. No private recording is needed.

## What to look for

| Signal | Meaning |
|---|---|
| S1 | Claimed institutional authority |
| S2 | Fear or pressure |
| S3 | Isolation or secrecy demands |
| S4 | App installation, link interaction, or sensitive information request |
| S5 | Money transfer request or payment details |
| N1 / N2 | Encouraged consultation / independent official verification |
| N3 / N4 | User-initiated call / information-only ending |

Normal institutions can trigger S1 and S2. A family money request can produce a caution result; a familiar name is not identity verification. Score bands are 0–29.9 low (`안전`), 30–54.9 caution (`주의`), 55–79.9 warning (`경고`), and 80–100 danger (`위험`). A detected isolation demand with confidence at least 0.8 sets a minimum score of 80.

## How it works

The Vue browser sends mono 16 kHz PCM through a Spring WebSocket relay to AssemblyAI Streaming. Final transcript turns feed a Korean keyword detector and AssemblyAI LLM Gateway. The selected models are configurable; the development configuration uses `whisper-rt` for Korean streaming and `qwen3.5-4b-32k-fast` for signal extraction.

The model extracts signals; Java code calculates the score. Model evidence must match an entire transcript line, allowing whitespace differences and omitted speaker labels. S4/S5 evidence also passes a conservative action check. Risk-reducing signals and the isolation trigger require local rule confirmation. Risk signals accumulate during a call; negative signals are reassessed. The score combines weighted signals, adjacent detected stages, and negative signals; the chain is not proof of chronological causation.

Stopping audio waits for the final upstream transcript and assessment. Rate limits temporarily leave only rule detection available. Final assessment can wait for one bounded cooldown retry.

## Validation and limits

Run backend tests with `./gradlew test bootJar` and frontend compilation with `npm run build`. Tests use mocked external responses; they do not prove live model accuracy. `tools/eval-detector.mjs` measures raw text model output and does not reproduce the production pipeline.

The included seven audio scenarios are synthetic. They are a demonstration set, not a representative evaluation dataset. Model variation, transcription errors, speaker attribution, and unusual wording can cause false positives and missed signals. Strict evidence validation can also discard a correct detection with an incomplete quote. The UI and spoken warning are currently Korean. Browser speech synthesis may be unavailable; the visual warning remains visible.

## Container and hosting

```sh
docker build -t guardline .
docker run --rm -p 8080:8080 --env-file .env guardline
```

Create a local `.env` with `ASSEMBLYAI_API_KEY`, and configure `CORS_ALLOWED_ORIGINS` to the exact public origin for a hosted instance. Use HTTPS/WSS. The Dockerfile builds both parts and serves the browser app, scenario assets, and WebSocket endpoint from one Java process. Run backend tests before building the image; the image build skips tests.

| Variable | Default / purpose |
|---|---|
| `ASSEMBLYAI_API_KEY` | Required secret; streaming and LLM Gateway |
| `GUARDLINE_LLM_MODEL` | `qwen3.5-4b-32k-fast` |
| `GUARDLINE_DAILY_SESSIONS` | 50 session starts |
| `GUARDLINE_CONCURRENT_SESSIONS` | 2 active audio sessions |
| `GUARDLINE_MAX_SESSION_MS` | 180000 ms audio session limit |
| `CORS_ALLOWED_ORIGINS` | Local dev origin; set the public origin when hosted |
| `PORT` | 8080 in the container |

Session counters are held in memory in a single instance and reset on process restart. They are usage controls, not a durable billing cap. Hosting can be free while AssemblyAI usage is billable. Verify provider-side usage limits before sharing a public live demo. A public deployment has not yet been verified.

## Repository

- `backend/`: streaming relay, signal extraction, scoring, and tests.
- `frontend/`: playback, transcript, evidence, and simulated intervention UI.
- `scenarios/`: synthetic scripts and generated audio with provenance in `manifest.json`.
- `tools/`: synthetic audio generation and diagnostic text evaluation.

Built by Hong DaeWoon as a solo project. AI coding assistance was used for implementation and verification. Synthetic voices were generated with `edge-tts`; audio is not a real call recording.

## License

Original Guard Line code and documentation are licensed under [MIT](LICENSE), copyright (c) 2026 Hong DaeWoon. Third-party dependencies, services, and generated voice assets retain their own rights and terms; see [third-party notices](THIRD_PARTY_NOTICES.md).
