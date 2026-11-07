/** Live-transcript window fed by the backend connection. */
export function Transcript({
  visible,
  entries = [],
  banner = null,
}: {
  visible: boolean;
  entries?: { speaker_id: string; text: string }[];
  banner?: string | null;
}) {
  const last3 = entries.slice(-3);
  return (
    <div
      id="asr-box"
      className="fixed right-6 top-6 z-40 w-80 border border-neon/40 bg-background/70 p-4 backdrop-blur transition-all duration-700"
      style={{ opacity: visible ? 1 : 0, transform: visible ? "none" : "translateY(-12px)" }}
    >
      <div className="mb-2 flex items-center justify-between font-mono text-[10px] uppercase tracking-[0.2em]">
        <span className="flex items-center gap-2 text-neon">
          <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-neon glow" />
          ASR
        </span>
        <span className="text-muted-foreground">ru-RU</span>
      </div>
      {banner && (
        <p className="mb-2 border border-amber-500/40 bg-amber-500/10 p-2 font-mono text-[10px] text-amber-400">
          {banner}
        </p>
      )}
      {last3.length ? (
        <div className="space-y-2">
          {last3.map((entry, i) => (
            <div key={i} className="font-mono text-sm leading-relaxed">
              <span className="text-neon">{String(entry.speaker_id)}</span>
              <span className="text-muted-foreground"> — </span>
              <span className="text-foreground">{entry.text}</span>
            </div>
          ))}
        </div>
      ) : (
        <p className="min-h-[3rem] font-mono text-sm leading-relaxed text-muted-foreground">
          тут распознанный текст<span className="caret text-neon">▍</span>
        </p>
      )}
    </div>
  );
}
