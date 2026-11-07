import { useEffect, useRef, useState } from "react";

/**
 * WebSocket connection to the presentation backend.
 *
 * The backend drives checkpoints; this hook mirrors its state into React.
 * Read-only by contract: `role=screen` sockets are ignored by the server when
 * they send anything, so navigation is the presenter console's job alone and
 * this hook exposes no command channel at all.
 * Falls back to standalone mode (local navigation) when the server is
 * unreachable.
 */

export interface BackendTranscriptRow {
  speaker_id: string;
  text: string;
  start_ms?: number;
  end_ms?: number;
  source?: string;
}

export interface PhoneStatus {
  connected: boolean;
  recording: boolean;
  processing: boolean;
  stub: boolean;
  engine: string | null;
}

export interface BackendState {
  connected: boolean;
  slideIndex: number;
  slideId: string;
  slideLabel: string;
  revision: number;
  demoPhase: "idle" | "live" | "fallback";
  banner: string | null;
  transcript: BackendTranscriptRow[];
  phone: PhoneStatus;
}

interface BackendMessage {
  type?: string;
  slide_index?: number;
  slide_id?: string;
  slide_label?: string;
  revision?: number;
  demo_phase?: string;
  banner?: string | null;
  transcript?: BackendTranscriptRow[];
  phone?: PhoneStatus;
  row?: BackendTranscriptRow;
  rows?: BackendTranscriptRow[];
  connected?: boolean;
  recording?: boolean;
  processing?: boolean;
  stub?: boolean;
  engine?: string | null;
  phase?: string;
  t?: number;
}

/**
 * Backend origin. Under `vite dev` the deck and the backend are on different
 * ports, so the dev default points at 8080 explicitly. A production build is
 * served by the backend itself, so the deck stays on the page origin: no CORS
 * preflights and no second host to keep in sync during the talk.
 */
function httpBase(): string {
  const env = (import.meta as unknown as { env?: Record<string, string | boolean> }).env;
  const configured = env?.["VITE_BACKEND_URL"];
  if (typeof configured === "string" && configured) return configured.replace(/\/+$/, "");
  if (env?.["DEV"]) return "http://localhost:8080";
  return window.location.origin;
}

function wsBase(): string {
  return httpBase().replace(/^http/, "ws");
}

const INITIAL: BackendState = {
  connected: false,
  slideIndex: 0,
  slideId: "qr",
  slideLabel: "QR",
  revision: 0,
  demoPhase: "idle",
  banner: null,
  transcript: [],
  phone: { connected: false, recording: false, processing: false, stub: false, engine: null },
};

export function useBackendConnection(): BackendState {
  const [state, setState] = useState<BackendState>(INITIAL);
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectDelay = useRef(800);
  const reconnectTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;

    void fetch(`${httpBase()}/v1/frontend/checkpoint`, { cache: "no-store" })
      .then((r) => (r.ok ? r.json() : null))
      .then((snapshot: BackendMessage | null) => {
        if (!snapshot || !mounted.current) return;
        setState((prev) => ({
          ...prev,
          slideIndex: snapshot.slide_index ?? 0,
          slideId: snapshot.slide_id ?? "qr",
          slideLabel: snapshot.slide_label ?? "QR",
          revision: snapshot.revision ?? 0,
          demoPhase: (snapshot.demo_phase as BackendState["demoPhase"]) ?? "idle",
          phone: snapshot.phone ?? prev.phone,
        }));
      })
      .catch(() => {
        /* standalone mode */
      });

    function connect() {
      if (!mounted.current) return;
      let ws: WebSocket;
      try {
        ws = new WebSocket(`${wsBase()}/v1/present-connect?role=screen`);
      } catch {
        scheduleReconnect();
        return;
      }
      wsRef.current = ws;

      ws.addEventListener("open", () => {
        reconnectDelay.current = 800;
        setState((prev) => ({ ...prev, connected: true }));
      });

      ws.addEventListener("message", (event) => {
        let msg: BackendMessage;
        try {
          msg = JSON.parse(event.data as string) as BackendMessage;
        } catch {
          return;
        }
        const type = msg.type;
        switch (type) {
          case "snapshot":
            setState((prev) => ({
              connected: true,
              slideIndex: msg.slide_index ?? 0,
              slideId: msg.slide_id ?? "qr",
              slideLabel: msg.slide_label ?? "QR",
              revision: msg.revision ?? 0,
              demoPhase: (msg.demo_phase as BackendState["demoPhase"]) ?? "idle",
              banner: msg.banner ?? null,
              transcript: Array.isArray(msg.transcript) ? msg.transcript : prev.transcript,
              phone: msg.phone ?? prev.phone,
            }));
            break;
          case "checkpoint":
            setState((prev) => ({
              ...prev,
              slideIndex: msg.slide_index ?? prev.slideIndex,
              slideId: msg.slide_id ?? prev.slideId,
              slideLabel: msg.slide_label ?? prev.slideLabel,
              revision: msg.revision ?? prev.revision,
              demoPhase: (msg.demo_phase as BackendState["demoPhase"]) ?? prev.demoPhase,
            }));
            break;
          case "transcript.final":
          case "transcript.partial": {
            const row = msg.row;
            if (!row) break;
            setState((prev) => ({
              ...prev,
              transcript: [...prev.transcript, row].slice(-60),
            }));
            break;
          }
          case "transcript.replay": {
            const rows = Array.isArray(msg.rows) ? msg.rows : [];
            setState((prev) => ({
              ...prev,
              transcript: rows,
              banner: msg.banner ?? prev.banner,
            }));
            break;
          }
          case "transcript.clear":
            setState((prev) => ({ ...prev, transcript: [] }));
            break;
          case "phone":
            setState((prev) => ({
              ...prev,
              phone: {
                connected: msg.connected ?? false,
                recording: msg.recording ?? false,
                processing: msg.processing ?? false,
                stub: msg.stub ?? false,
                engine: msg.engine ?? null,
              },
            }));
            break;
          case "demo":
            setState((prev) => ({
              ...prev,
              demoPhase: (msg.phase as BackendState["demoPhase"]) ?? prev.demoPhase,
              banner: msg.banner ?? null,
            }));
            break;
          case "ping":
            try {
              ws.send(JSON.stringify({ type: "pong", t: msg.t }));
            } catch {
              /* closing */
            }
            break;
          default:
            break;
        }
      });

      ws.addEventListener("close", () => {
        setState((prev) => ({ ...prev, connected: false }));
        scheduleReconnect();
      });
      ws.addEventListener("error", () => {
        try {
          ws.close();
        } catch {
          /* already closed */
        }
      });
    }

    function scheduleReconnect() {
      if (!mounted.current) return;
      if (reconnectTimer.current) clearTimeout(reconnectTimer.current);
      reconnectTimer.current = setTimeout(() => {
        reconnectDelay.current = Math.min(8000, reconnectDelay.current * 1.7);
        connect();
      }, reconnectDelay.current);
    }

    connect();

    return () => {
      mounted.current = false;
      if (reconnectTimer.current) clearTimeout(reconnectTimer.current);
      wsRef.current?.close();
      wsRef.current = null;
    };
  }, []);

  return state;
}
