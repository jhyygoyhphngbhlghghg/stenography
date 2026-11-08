import { useEffect, useRef } from "react";
import type { BackendTranscriptRow } from "./useBackendConnection";
import { useMicrophone } from "./useMicrophone";
import { voiceSignal } from "./dialogue";

/**
 * Слайд «Демо». Живое — в ленте слева и в телефоне справа, поэтому здесь
 * только состояние микрофона: тот самый момент, когда людям на сцене пора
 * говорить.
 *
 * Формулировка честная: поток из браузера питает визуализацию голоса,
 * распознавание остаётся на телефоне.
 */
export function LiveDemo({
  compact,
  active = false,
  autoMic = true,
  backendEntries = [],
}: {
  compact: boolean;
  active?: boolean;
  /** false, когда до слайда долистали сами: запрос не должен вспыхнуть у всего зала. */
  autoMic?: boolean;
  backendEntries?: BackendTranscriptRow[];
}) {
  const root = useRef<HTMLDivElement>(null);
  const { state, start } = useMicrophone(active, autoMic);
  const rows = backendEntries.length;
  const speakers = new Set(backendEntries.map((row) => row.speaker_id)).size;

  // Амплитуда уходит в CSS, а не в React: перерисовка дерева на каждый кадр
  // обрушила бы частоту переходов между слайдами.
  useEffect(() => {
    if (!active || state !== "on") return;
    let frame = 0;
    const tick = () => {
      frame = requestAnimationFrame(tick);
      const level = voiceSignal.connected ? 0.15 + voiceSignal.level : 0.08;
      root.current?.style.setProperty("--voice-envelope", String(level));
    };
    tick();
    return () => cancelAnimationFrame(frame);
  }, [active, state]);

  return (
    <div ref={root} className="demo-stage" data-demo data-mic={state}>
      <p className="a kicker">// 04 — демо</p>
      <h2 className="a mt-6 text-5xl font-medium tracking-tight md:text-7xl">
        Живой показ<span className="text-neon caret">_</span>
      </h2>
      <p className="a mt-8 max-w-2xl text-xl leading-relaxed text-muted-foreground">
        Шесть человек говорят по очереди в один микрофон телефона. Разделение голосов,
        распознавание и пунктуация считаются на устройстве, в авиарежиме.
      </p>

      <div className="a mic-control">
        {state === "on" ? (
          <>
            <span className="mic-meter" aria-hidden>
              {Array.from({ length: 14 }, (_, i) => (
                <i key={i} style={{ animationDelay: `${i * 45}ms` }} />
              ))}
            </span>
            <span className="mic-note">
              микрофон включён · голос питает визуализацию, распознавание остаётся на телефоне
            </span>
          </>
        ) : state === "requesting" ? (
          <span className="mic-note">запрашиваю доступ к микрофону…</span>
        ) : state === "unsupported" ? (
          <span className="mic-note">этот браузер не даёт доступ к микрофону</span>
        ) : (
          <>
            <button type="button" className="mic-button" onClick={() => void start()}>
              включить микрофон
            </button>
            <span className="mic-note">
              {state === "denied"
                ? "браузер не дал доступ — разрешите его в настройках сайта"
                : "нужен для момента, когда на сцене говорят"}
            </span>
          </>
        )}
      </div>

      {!compact && (
        <p className="a mt-6 font-mono text-xs uppercase tracking-[0.2em] text-muted-foreground">
          {rows ? `${rows} строк · ${speakers} голосов` : "для демонстрации потребуются люди из аудитории"}
        </p>
      )}
    </div>
  );
}
