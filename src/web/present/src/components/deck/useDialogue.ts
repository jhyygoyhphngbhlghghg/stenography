import { useEffect, useSyncExternalStore } from "react";
import {
  getInputSnapshot,
  receiveDialogue,
  receiveTelemetry,
  resetDialogue,
  subscribeInput,
  voiceSignal,
} from "./dialogue";

let detachVoice: (() => void) | undefined;
let voiceGeneration = 0;
export async function attachVoiceStream(stream: MediaStream) {
  const generation = ++voiceGeneration;
  detachVoice?.();
  const context = new AudioContext();
  let source: MediaStreamAudioSourceNode;
  try {
    await context.resume();
    if (generation !== voiceGeneration) {
      await context.close();
      return () => {};
    }
    source = context.createMediaStreamSource(stream);
  } catch (error) {
    await context.close();
    throw error;
  }
  const analyser = context.createAnalyser();
  analyser.fftSize = 256;
  analyser.smoothingTimeConstant = 0.85;
  source.connect(analyser); // No speaker output or feedback.
  const samples = new Float32Array(analyser.fftSize);
  let frame = 0;
  voiceSignal.connected = true;
  const sample = () => {
    analyser.getFloatTimeDomainData(samples);
    const rms = Math.sqrt(samples.reduce((sum, n) => sum + n * n, 0) / samples.length);
    voiceSignal.level += (Math.min(1, rms * 5) - voiceSignal.level) * 0.16;
    frame = requestAnimationFrame(sample);
  };
  sample();
  let closed = false;
  const cleanup = () => {
    if (closed) return;
    closed = true;
    cancelAnimationFrame(frame);
    source.disconnect();
    void context.close();
    if (detachVoice === cleanup) {
      detachVoice = undefined;
      voiceSignal.connected = false;
      voiceSignal.level = 0;
    }
  };
  detachVoice = cleanup;
  return cleanup;
}
const presentationInput = {
  receiveDialogue,
  receiveTelemetry,
  attachVoiceStream,
  reset: resetDialogue,
};
declare global {
  interface Window {
    presentationInput?: typeof presentationInput;
  }
}

export function useDialogue() {
  return useSyncExternalStore(subscribeInput, getInputSnapshot, getInputSnapshot);
}
export function usePresentationInput() {
  useEffect(() => {
    window.presentationInput = presentationInput;
    const handle = (event: Event) => receiveDialogue((event as CustomEvent<unknown>).detail);
    window.addEventListener("presentation:dialogue", handle);
    return () => {
      window.removeEventListener("presentation:dialogue", handle);
      delete window.presentationInput;
      voiceGeneration++;
      detachVoice?.();
    };
  }, []);
}
