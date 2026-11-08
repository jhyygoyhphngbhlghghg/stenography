import { useCallback, useEffect, useRef, useState } from "react";
import { attachVoiceStream } from "./useDialogue";

/**
 * Микрофон для момента, когда на сцене говорят люди.
 *
 * Важно, что этот поток делает, а чего нет: он только питает амплитуду
 * (`voiceSignal`) для визуализации. Распознавание, диаризация и пунктуация
 * остаются на телефоне — браузер ничего не слушает "в модель", и на экране это
 * сказано теми же словами (см. INPUT.md).
 *
 * Поток запрашивается при входе на чекпоинт демо. Safari и iOS требуют жеста,
 * поэтому автозапрос часто получает NotAllowedError: в этом случае состояние
 * становится `denied`, и UI показывает явную кнопку, а не делает вид, что
 * микрофон работает.
 */
export type MicState = "off" | "requesting" | "on" | "denied" | "unsupported";

export function useMicrophone(active: boolean, autoStart = true) {
  const [state, setState] = useState<MicState>("off");
  const stream = useRef<MediaStream | null>(null);
  const detach = useRef<(() => void) | null>(null);

  const stop = useCallback(() => {
    detach.current?.();
    detach.current = null;
    for (const track of stream.current?.getTracks() ?? []) track.stop();
    stream.current = null;
    setState((current) => (current === "unsupported" ? current : "off"));
  }, []);

  const start = useCallback(async () => {
    if (!navigator.mediaDevices?.getUserMedia) {
      setState("unsupported");
      return false;
    }
    setState("requesting");
    try {
      const media = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true },
      });
      stream.current = media;
      detach.current = await attachVoiceStream(media);
      setState("on");
      return true;
    } catch (error) {
      const name = error instanceof DOMException ? error.name : "";
      stream.current = null;
      setState(name === "NotAllowedError" || name === "SecurityError" ? "denied" : "off");
      return false;
    }
  }, []);

  // Уход с чекпоинта обязан погасить индикатор: горящий микрофон на другом
  // слайде был бы неправдой. autoStart=false — это зал, долиставший до демо
  // сам: prompt у каждого зрителя был бы вторжением, кнопка на слайде остаётся.
  useEffect(() => {
    if (active && autoStart) void start();
    return stop;
  }, [active, autoStart, start, stop]);

  return { state, start, stop };
}
