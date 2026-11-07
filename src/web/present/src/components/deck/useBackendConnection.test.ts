import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useBackendConnection } from "./useBackendConnection";

/**
 * The lock lives on the server; the deck only ever reads it. These tests pin
 * the three ways the flag arrives — HTTP snapshot on boot, `snapshot` on socket
 * open, `checkpoint` and the `screen.scroll` broadcast — because a flag that
 * reaches the deck through only one of them leaves part of the room scrolling
 * while the rest is frozen.
 */

class FakeSocket {
  static instance: FakeSocket | null = null;
  listeners = new Map<string, ((value: unknown) => void)[]>();
  sent: string[] = [];
  readyState = 1;

  constructor(public url: string) {
    FakeSocket.instance = this;
  }

  addEventListener(type: string, cb: (value: unknown) => void) {
    const list = this.listeners.get(type) ?? [];
    list.push(cb);
    this.listeners.set(type, list);
  }

  emit(type: string, payload: unknown) {
    for (const cb of this.listeners.get(type) ?? []) {
      cb({ data: JSON.stringify(payload) });
    }
  }

  send(data: string) {
    this.sent.push(data);
  }

  close() {}
}

function openSocket() {
  const socket = FakeSocket.instance;
  if (!socket) throw new Error("socket was not constructed");
  act(() => socket.emit("open", {}));
  return socket;
}

beforeEach(() => {
  FakeSocket.instance = null;
  vi.stubGlobal("WebSocket", FakeSocket);
  vi.stubGlobal("fetch", vi.fn(async () => ({ ok: true, json: async () => ({}) })));
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("audience scroll lock", () => {
  it("starts locked, so a screen that comes up mid-talk never drifts", async () => {
    const { result } = renderHook(() => useBackendConnection());
    await waitFor(() => expect(result.current.connected).toBe(false));
    expect(result.current.scrollLocked).toBe(true);
  });

  it("takes the lock from the snapshot the socket sends on open", async () => {
    const { result } = renderHook(() => useBackendConnection());
    openSocket().emit("message", { type: "snapshot", slide_index: 3, scroll_locked: false });
    await waitFor(() => expect(result.current.scrollLocked).toBe(false));
    expect(result.current.slideIndex).toBe(3);
  });

  it("keeps the lock from the checkpoint payload a late screen polls", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => ({
        ok: true,
        json: async () => ({ slide_index: 5, scroll_locked: false }),
      })),
    );
    const { result } = renderHook(() => useBackendConnection());
    await waitFor(() => expect(result.current.scrollLocked).toBe(false));
    expect(result.current.slideIndex).toBe(5);
  });

  it("follows the console live when it releases or restores the lock", async () => {
    const { result } = renderHook(() => useBackendConnection());
    const socket = openSocket();

    socket.emit("message", { type: "screen.scroll", on: true });
    await waitFor(() => expect(result.current.scrollLocked).toBe(false));

    socket.emit("message", { type: "checkpoint", revision: 9, scroll_locked: true });
    await waitFor(() => expect(result.current.scrollLocked).toBe(true));
    expect(result.current.revision).toBe(9);
  });

  it("never lets the cold-start poll drag a live screen backwards", async () => {
    let resolveFetch: (value: unknown) => void = () => {};
    vi.stubGlobal(
      "fetch",
      vi.fn(() => new Promise((resolve) => (resolveFetch = resolve))),
    );

    const { result } = renderHook(() => useBackendConnection());
    const socket = openSocket();
    socket.emit("message", {
      type: "snapshot",
      slide_index: 6,
      revision: 42,
      scroll_locked: false,
    });
    await waitFor(() => expect(result.current.slideIndex).toBe(6));

    // Опрос стартовал раньше, а пришёл позже: он обязан промолчать, иначе зал
    // откатится на прошлый слайд ровно в тот момент, когда ведущий едет вперёд.
    act(() => {
      resolveFetch({ ok: true, json: async () => ({ slide_index: 0, revision: 1 }) });
    });
    await waitFor(() => expect(result.current.revision).toBe(42));
    expect(result.current.slideIndex).toBe(6);
    expect(result.current.scrollLocked).toBe(false);
  });

  it("stays read-only: the screen never sends a command", async () => {
    const { result } = renderHook(() => useBackendConnection());
    const socket = openSocket();
    socket.emit("message", { type: "screen.scroll", on: true });
    await waitFor(() => expect(result.current.scrollLocked).toBe(false));
    expect(socket.sent.every((frame) => !frame.includes("screen.scroll"))).toBe(true);
  });
});
