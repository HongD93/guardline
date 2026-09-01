import { createReadStream, statSync } from 'node:fs';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

const SCENARIOS_DIR = fileURLToPath(new URL('../scenarios', import.meta.url));
const MIME = { '.mp3': 'audio/mpeg', '.json': 'application/json' };

/**
 * 검증용 시나리오 음성과 manifest는 frontend 밖(../scenarios)에 있다. 파일을 복제해두면
 * 대사를 고칠 때마다 두 곳이 어긋나므로, 개발 서버가 원본 디렉터리를 그대로 서빙한다.
 */
function scenarioAssets() {
  return {
    name: 'guardline-scenario-assets',
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
