import { speakerColor, speakerLabel } from "./speakers";

/**
 * Живая лента показа. Стоит в левом верхнем углу, сразу справа от подписи
 * команды, — так её видят и зал, и ведущий, не отрывая глаз от сцены.
 *
 * Полоски появляются только у тех спикеров, чьи реплики реально пришли:
 * заранее «разложенных» шести голосов на экране нет. Цвет привязан к
 * speaker_id, поэтому один и тот же человек всегда одного цвета.
 */
export function Transcript({
  visible,
  entries = [],
  banner = null,
  rows = 4,
}: {
  visible: boolean;
  entries?: { speaker_id: string; text: string }[];
  banner?: string | null;
  /** How many recent utterances to keep on screen. */
  rows?: number;
}) {
  const last = entries.slice(-Math.max(1, rows));

  return (
    <div
      id="asr-box"
      className="live-feed"
      data-visible={visible ? "true" : "false"}
      aria-hidden={!visible}
    >
      <div className="live-feed-head font-mono">
        <span className="live-dot" />
        LIVE
        {banner && <span className="live-feed-flag">{banner}</span>}
      </div>
      {last.length ? (
        <ul className="live-feed-rows">
          {last.map((entry, i) => (
            <li
              key={`${entry.speaker_id}-${i}`}
              className="live-feed-row"
              style={{ "--speaker": speakerColor(entry.speaker_id) } as React.CSSProperties}
            >
              <span className="live-feed-bar" aria-hidden />
              <span className="live-feed-name font-mono">{speakerLabel(entry.speaker_id)}</span>
              <span className="live-feed-text">{entry.text}</span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="live-feed-empty font-mono">
          пока тишина<span className="caret">▍</span>
        </p>
      )}
    </div>
  );
}
