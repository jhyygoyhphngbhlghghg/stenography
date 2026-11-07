/** Boots the built server on a free port and shuts it down again. */
import { spawn, type ChildProcess } from 'node:child_process';
import { once } from 'node:events';
import { resolve } from 'node:path';

export interface RunningServer {
  child: ChildProcess;
  base: string;
  port: number;
  stop: () => Promise<void>;
}

const ENTRY = resolve(import.meta.dirname ?? '.', '../dist/server.js');

export async function startServer(env: Record<string, string> = {}): Promise<RunningServer> {
  const port = 8200 + Math.floor(Math.random() * 600);
  const child = spawn(process.execPath, [ENTRY], {
    env: { ...process.env, PORT: String(port), HOST: '127.0.0.1', ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  let stderr = '';
  child.stderr?.on('data', (chunk: Buffer) => { stderr += chunk.toString(); });

  const exited = once(child, 'exit');
  const race = await Promise.race([
    exited.then(([code]) => ({ exited: true as const, code })),
    waitForHealth(port).then(() => ({ exited: false as const })),
  ]);
  if (race.exited) throw new Error(`server exited early (code ${race.code}): ${stderr}`);

  return {
    child,
    port,
    base: `http://127.0.0.1:${port}`,
    async stop() {
      child.kill('SIGTERM');
      await Promise.race([once(child, 'exit'), sleep(2000)]);
      if (child.exitCode === null) child.kill('SIGKILL');
    },
  };
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

async function waitForHealth(port: number, tries = 60): Promise<void> {
  for (let i = 0; i < tries; i += 1) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/v1/health`);
      if (response.ok) return;
    } catch {
      /* not up yet */
    }
    await sleep(50);
  }
  throw new Error('server did not become healthy');
}
