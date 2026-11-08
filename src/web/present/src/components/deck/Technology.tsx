import { lazy, Suspense } from "react";
import { ClientOnly } from "@tanstack/react-router";
import { useDialogue } from "./useDialogue";
import { voiceSignal } from "./dialogue";
const Pipeline3D = lazy(() => import("./Pipeline3D"));

export function Technology({
  active,
  warm,
  compact,
}: {
  active: boolean;
  warm: boolean;
  compact: boolean;
}) {
  const { entries } = useDialogue();
  const latest = entries.at(-1);
  return (
    <div className={`technology-workspace ${active ? "is-active" : ""}`} data-technology>
      <p className="a kicker">// 05 — технология</p>
      <h2 className="a mt-4 text-4xl font-medium">От голоса к диалогу</h2>
      {compact ? (
        <div className="compact-pipeline">
          <div className="compact-audio font-mono">
            <span className="text-neon">01 / AUDIO IN</span>
            <p className="text-muted-foreground">Голос ведущего</p>
            <div className="compact-wave" aria-hidden>
              {Array.from({ length: 16 }, (_, i) => (
                <i key={i} />
              ))}
            </div>
          </div>
          <div className="compact-model font-mono">
            <span className="text-neon">02 / ASR MODEL</span>
            <small className="text-muted-foreground">audio → tokens → text</small>
          </div>
          <div className="compact-transcript font-mono">
            <span className="text-neon">03 / TRANSCRIPT</span>
            <p>{latest?.text ?? "[транскрипция]"}</p>
          </div>
        </div>
      ) : (
        <div className="pipeline-stage">
          <div className="pipeline-source font-mono text-muted-foreground">
            <span className="text-neon">01 / AUDIO IN</span>
            <p>{latest?.text ?? "Голос ведущего"}</p>
          </div>
          <div className="pipeline-canvas">
            {warm && (
              <ClientOnly>
                <Suspense fallback={null}>
                  <Pipeline3D active={active} />
                </Suspense>
              </ClientOnly>
            )}
          </div>
          <div className="pipeline-model model-one font-mono">
            <span>ASR MODEL</span>
            <small>audio → tokens</small>
          </div>
          <div className="pipeline-model model-two font-mono">
            <span>TEXT DECODER</span>
            <small>tokens → text</small>
          </div>
          <div className="pipeline-output font-mono">
            <span className="text-neon">03 / TRANSCRIPT</span>
            <p>
              {latest?.text ?? "[транскрипция]"}
              <span className="caret text-neon">▍</span>
            </p>
          </div>
          <div className="pipeline-config font-mono text-muted-foreground">
            <span>SCHEMATIC / CONFIG PREVIEW</span>
            <p>
              stream: audio · mode: realtime
              <br />
              speaker.verify: false
              <br />
              input: {voiceSignal.connected ? "live stream" : "awaiting stream"}
            </p>
          </div>
        </div>
      )}
      <div className="tech-bottom a">
        <p className="technology-explanation text-muted-foreground">
          Аудиопоток поступает в модель распознавания. Она выделяет речевые признаки и декодирует их
          в текст; новые фрагменты пополняют цепочку диалога.
        </p>
        {!compact && (
          <div className="terminal-preview font-mono text-muted-foreground">
            <span>SCHEMATIC / BOOT SEQUENCE</span>
            <p>
              <b className="text-neon">›</b> initialize audio stream
              <br />
              <b className="text-neon">›</b> connect inference pipeline
              <br />
              <b className="text-neon">›</b> emit transcription<span className="caret">_</span>
            </p>
          </div>
        )}
      </div>
    </div>
  );
}
