import { useState } from "react";
import { displayName, setName, useSpeakerNames } from "./speakerNames";
import { speakerColor, speakerLabel } from "./speakers";

/**
 * Живая лента показа: левый верх, сразу справа от подписи команды.
 *
 * Полоски появляются только у тех спикеров, чьи реплики реально пришли. Цвет
 * привязан к speaker_id, поэтому один и тот же человек всегда одного цвета.
 *
 * Баннер резервного режима здесь не печатается: он один и должен быть один —
 * в статусной строке над лентой. В прошлом скрине он стоял дважды и вторым
 * съедал место, на которое ложился заголовок слайда.
 */
export function Transcript({
  visible,
  entries = [],
  rows = 4,
}: {
  visible: boolean;
  entries?: { speaker_id: string; text: string }[];
  /** How many recent utterances to keep on screen. */
  rows?: number;
}) {
  useSpeakerNames();
  const [editing, setEditing] = useState<string | null>(null);
  const last = entries.slice(-Math.max(1, rows));

  return (
    <div id="asr-box" className="live-feed" data-visible={visible ? "true" : "false"} aria-hidden={!visible}>
      <div className="live-feed-head font-mono">
        <span className="live-dot" />
        LIVE
      </div>
      {last.length ? (
        <ul className="live-feed-rows">
          {last.map((entry, i) => {
            const label = displayName(entry.speaker_id);
            const numeric = label === entry.speaker_id ? speakerLabel(entry.speaker_id) : label;
            return (
              <li
                key={`${entry.speaker_id}-${i}`}
                className="live-feed-row"
                style={{ "--speaker": speakerColor(entry.speaker_id) } as React.CSSProperties}
              >
                <span className="live-feed-bar" aria-hidden />
                {editing === entry.speaker_id ? (
                  <input
                    autoFocus
                    className="live-feed-input font-mono"
                    aria-label={`Имя спикера ${entry.speaker_id}`}
                    defaultValue={label}
                    maxLength={40}
                    onBlur={(event) => {
                      setName(entry.speaker_id, event.currentTarget.value);
                      setEditing(null);
                    }}
                    onKeyDown={(event) => {
                      if (event.key === "Enter") {
                        setName(entry.speaker_id, event.currentTarget.value);
                        setEditing(null);
                      } else if (event.key === "Escape") setEditing(null);
                    }}
                  />
                ) : (
                  <button
                    type="button"
                    className="live-feed-name font-mono"
                    aria-label={`Переименовать ${entry.speaker_id}`}
                    onClick={() => setEditing(entry.speaker_id)}
                  >
                    {numeric}
                  </button>
                )}
                <span className="live-feed-text">{entry.text}</span>
              </li>
            );
          })}
        </ul>
      ) : (
        <p className="live-feed-empty font-mono">
          пока тишина<span className="caret">▍</span>
        </p>
      )}
    </div>
  );
}
