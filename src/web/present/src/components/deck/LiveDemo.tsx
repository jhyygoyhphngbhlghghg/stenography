import { useDialogue } from "./useDialogue";
import { useEffect, useRef } from "react";
import { voiceSignal } from "./dialogue";
import type { BackendTranscriptRow } from "./useBackendConnection";

export function LiveDemo({
  compact,
  active,
  backendEntries = [],
}: {
  compact: boolean;
  active: boolean;
  backendEntries?: BackendTranscriptRow[];
}) {
  const root = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (compact || !active) return;
    let frame = 0;
    const tick = () => {
      root.current?.style.setProperty(
        "--voice-envelope",
        String(voiceSignal.connected ? 0.15 + voiceSignal.level : 1),
      );
      frame = requestAnimationFrame(tick);
    };
    tick();
    return () => cancelAnimationFrame(frame);
  }, [compact, active]);
  const { entries: localEntries, telemetry } = useDialogue();
  // Merge backend transcript rows with local dialogue entries
  const backendAsEntries = backendEntries.map((row, i) => ({
    speaker_id: row.speaker_id,
    text: row.text,
    sequence: 10000 + i,
  }));
  const entries = [...localEntries, ...backendAsEntries];
  const speakers = [...new Set(entries.map((entry) => String(entry.speaker_id)))];
  const allRows = speakers.length ? speakers : ["01", "02"];
  const rows = compact ? allRows.slice(-3) : allRows;
  return (
    <div ref={root} className="demo-workspace a" data-demo>
      <header className="demo-header">
        <span className="font-mono text-xs uppercase text-neon">
          <span className="live-dot" />
          Live demo
        </span>
        <div className="demo-telemetry font-mono text-xs text-muted-foreground">
          <span>
            RAM{" "}
            <strong className="text-foreground">
              {telemetry.ramMb === null ? "—" : `${Math.round(telemetry.ramMb)} MB`}
            </strong>
          </span>
          <span>
            CPU{" "}
            <strong className="text-foreground">
              {telemetry.cpuPercent === null ? "—" : `${telemetry.cpuPercent.toFixed(1)}%`}
            </strong>
          </span>
        </div>
      </header>
      <div className="dialogue-editor">
        <div className="timeline-ruler font-mono text-muted-foreground">
          <span>VOICE / DIALOGUE</span>
          {["00:00", "00:15", "00:30", "00:45"].map((t) => (
            <span key={t}>{t}</span>
          ))}
        </div>
        <div className="dialogue-lanes">
          {rows.map((speaker, index) => (
            <div className="dialogue-lane" key={speaker}>
              <div className="speaker-label font-mono">
                <span className="text-neon">{String(index + 1).padStart(2, "0")}</span>
                <span className="text-muted-foreground">
                  {speakers.length ? speaker : "SPEAKER"}
                </span>
              </div>
              <div className="dialogue-clips">
                {entries
                  .filter((e) => String(e.speaker_id) === speaker)
                  .slice(compact ? -1 : -4)
                  .map((entry) => (
                    <div
                      key={entry.sequence}
                      className={`dialogue-clip ${index % 2 ? "alternate" : ""}`}
                    >
                      <span className="font-mono text-muted-foreground">
                        #{String(entry.sequence).padStart(3, "0")}
                      </span>
                      <p>{entry.text}</p>
                      {!compact && (
                        <div className="clip-wave" aria-hidden>
                          {Array.from({ length: 28 }, (_, i) => (
                            <i
                              key={i}
                              style={{ height: `${20 + Math.abs(Math.sin(i * 1.7)) * 75}%` }}
                            />
                          ))}
                        </div>
                      )}
                    </div>
                  ))}
                {!entries.length && (
                  <div className="empty-track font-mono text-muted-foreground">—</div>
                )}
              </div>
            </div>
          ))}
        </div>
        <div className="timeline-playhead" aria-hidden>
          <span />
        </div>
        <footer className="timeline-footer font-mono text-muted-foreground">
          <span>JSON · speaker_id / text</span>
          <span>{entries.length ? `${entries.length} SEGMENTS` : "WAITING FOR INPUT"}</span>
        </footer>
      </div>
    </div>
  );
}
