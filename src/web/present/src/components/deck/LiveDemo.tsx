import type { BackendTranscriptRow } from "./useBackendConnection";

/**
 * Слайд «Демо». Специально пустой: живое — в ленте слева и в телефоне справа,
 * здесь показывать нечего. Таймлайн, дорожки и подписи убраны по замечанию
 * Лёвы: они спорили с реальным результатом за внимание.
 */
export function LiveDemo({
  compact,
  backendEntries = [],
}: {
  compact: boolean;
  active?: boolean;
  backendEntries?: BackendTranscriptRow[];
}) {
  const speakers = new Set(backendEntries.map((row) => row.speaker_id)).size;
  const rows = backendEntries.length;

  return (
    <div className="demo-stage" data-demo>
      <p className="a kicker">// 04 — демо</p>
      <h2 className="a mt-6 text-5xl font-medium tracking-tight md:text-7xl">
        Живой показ<span className="text-neon caret">_</span>
      </h2>
      <p className="a mt-8 max-w-2xl text-xl leading-relaxed text-muted-foreground">
        Шесть человек говорят по очереди в один микрофон телефона. Разделение голосов, распознавание
        и пунктуация считаются на устройстве, в авиарежиме.
      </p>
      {!compact && (
        <p className="a mt-10 font-mono text-xs uppercase tracking-[0.2em] text-muted-foreground">
          {rows
            ? `${rows} строк · ${speakers} голосов`
            : "для демонстрации потребуются люди из аудитории"}
        </p>
      )}
    </div>
  );
}
