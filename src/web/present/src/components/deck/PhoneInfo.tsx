import { useEffect, useRef } from "react";
import { useDialogue } from "./useDialogue";
import { voiceSignal } from "./dialogue";

/**
 * Схематичный телефон в правом верхнем углу: единственный источник звука на
 * сцене. Телеметрия живёт внутри корпуса и набрана мелко — она поясняет
 * картинку, а не спорит с ней за внимание.
 *
 * Прочерк вместо числа означает «значений не присылали»: цифр из воздуха тут
 * нет намеренно (см. INPUT.md).
 */
export function PhoneInfo({ visible }: { visible: boolean }) {
  const { telemetry } = useDialogue();
  const root = useRef<HTMLDivElement>(null);
  const ram = telemetry.ramMb === null ? "—" : `${Math.round(telemetry.ramMb)} MB`;
  const cpu = telemetry.cpuPercent === null ? "—" : `${telemetry.cpuPercent.toFixed(1)}%`;

  // The envelope changes every animation frame, so it goes to CSS rather than
  // to React: a re-render per frame would fight the checkpoint transitions.
  useEffect(() => {
    if (!visible) return;
    let frame = 0;
    const tick = () => {
      const level = voiceSignal.connected ? 0.15 + voiceSignal.level : 0.08;
      root.current?.style.setProperty("--voice-envelope", String(level));
      frame = requestAnimationFrame(tick);
    };
    tick();
    return () => cancelAnimationFrame(frame);
  }, [visible]);

  return (
    <div
      ref={root}
      className="phone-info"
      data-visible={visible ? "true" : "false"}
      aria-hidden={!visible}
    >
      <svg viewBox="0 0 96 168" className="phone-frame" role="img" aria-label="Телефон ведущего">
        <rect x="1.5" y="1.5" width="93" height="165" rx="14" className="phone-shell" />
        <rect x="9" y="20" width="78" height="120" rx="6" className="phone-screen" />
        <path d="M40 10h16" className="phone-speaker" />
        {/* Микрофон: высота полосок — амплитуда голоса, если поток подключён. */}
        <g className="phone-mic">
          {Array.from({ length: 9 }, (_, i) => (
            <rect
              key={i}
              x={16 + i * 8}
              y={126 - baseHeight(i)}
              width="4"
              height={baseHeight(i)}
              rx="1"
              style={{ transformOrigin: `${18 + i * 8}px 126px` }}
            />
          ))}
        </g>
      </svg>
      <dl className="phone-telemetry font-mono">
        <div>
          <dt>RAM</dt>
          <dd>{ram}</dd>
        </div>
        <div>
          <dt>CPU</dt>
          <dd>{cpu}</dd>
        </div>
      </dl>
    </div>
  );
}

function baseHeight(index: number): number {
  return 6 + Math.abs(Math.sin(index * 1.1)) * 14;
}
