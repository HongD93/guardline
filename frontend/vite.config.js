import { createReadStream, statSync } from 'node:fs';
import { cp } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

const SCENARIOS_DIR = fileURLToPath(new URL('../scenarios', import.meta.url));
const MIME = { '.mp3': 'audio/mpeg', '.json': 'application/json' };

/**
 * 검증용 시나리오 음성과 manifest는 frontend 밖(../scenarios)에 있다. 파일을 복제해두면
 * 대사를 고칠 때마다 두 곳이 어긋나므로 원본 디렉터리를 그대로 쓴다.
 *
 * <p>개발 서버는 미들웨어로 서빙하고, 프로덕션 빌드에는 dist/scenarios로 복사해 넣는다.
 * 복사를 빠뜨리면 배포본에서 재생 버튼이 아무 반응도 하지 않는다.
 */
function scenarioAssets() {
  return {
    name: 'guardline-scenario-assets',

    async closeBundle() {
      const outDir = fileURLToPath(new URL('./dist/scenarios', import.meta.url));
      await cp(SCENARIOS_DIR, outDir, { recursive: true });
      console.log(`  시나리오 자산 복사 완료 → dist/scenarios`);
    },

    configureServer(server) {
      server.middlewares.use('/scenarios', (req, res, next) => {
        const relative = decodeURIComponent(req.url.split('?')[0]);
        const target = normalize(join(SCENARIOS_DIR, relative));

        // 디렉터리 밖으로 빠져나가는 경로는 거부한다
        if (!target.startsWith(SCENARIOS_DIR)) {
          res.statusCode = 403;
          res.end('forbidden');
          return;
        }
        try {
          statSync(target);
        } catch {
          next();
          return;
        }
        res.setHeader('Content-Type', MIME[extname(target)] ?? 'application/octet-stream');
        createReadStream(target).pipe(res);
      });
    },
  };
}

export default defineConfig({
  plugins: [vue(), scenarioAssets()],
  server: {
    port: 5175,
    proxy: {
      // 릴레이 WebSocket. 프론트/백엔드 오리진이 갈라지지 않게 개발 중에는 프록시로 묶는다.
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
});
