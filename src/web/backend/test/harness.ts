/** Boots the built server on a free port and shuts it down again. */
import { spawn, type ChildProcess } from 'node:child_process';
import { once } from 'node:events';
import net from 'node:net';
import { resolve } from 'node:path';

export interface RunningServer {
  child: ChildProcess;
  base: string;
  port: number;
  stop: () => Promise<void>;
}

const ENTRY = resolve(import.meta.dirname ?? '.', '../dist/server.js');

/**
 * App settings that must never leak in from the shell. A developer who has
 * just sourced their deployment env would otherwise run the suite with a real
 * PHONE_TOKEN: every phone-role socket in these tests connects without a
 * bearer header, so it would be refused with 4001 and the failures would look
 * like product bugs. Each test that cares passes its own value explicitly.
 */
const NEVER_INHERITED = [
  'ADMIN_TOKEN',
  'PHONE_TOKEN',
  'PUBLIC_DIR',
  'PRESENT_DIR',
  'PING_INTERVAL_MS',
  'PHONE_STALL_MS',
  'DISPLAY_STALL_MS',
  'MAX_TRANSCRIPT_ROWS',
  'MAX_BODY_BYTES',
  'NODE_ENV',
];

function childEnv(env: Record<string, string>): Record<string, string> {
  const base: Record<string, string | undefined> = { ...process.env };
  for (const key of NEVER_INHERITED) delete base[key];
  return { ...base, HOST: '127.0.0.1', ...env } as Record<string, string>;
}

/**
 * An ephemeral port from the OS. The previous `8200 + random(600)` collided
 * across twenty concurrent suites and produced "server exited early"; asking
 * the kernel is narrower by orders of magnitude, and the leftover race is
 * covered by the health wait below.
 */
async function freePort(): Promise<number> {
  return new Promise((promiseResolve, promiseReject) => {
    const probe = net.createServer();
    probe.once('error', promiseReject);
    probe.listen(0, '127.0.0.1', () => {
      const { port } = probe.address() as net.AddressInfo;
      probe.close(() => promiseResolve(port));
    });
  });
}

export async function startServer(env: Record<string, string> = {}): Promise<RunningServer> {
  const port = await freePort();
  const child = spawn(process.execPath, [ENTRY], {
    env: childEnv({ PORT: String(port), ...env }),
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
