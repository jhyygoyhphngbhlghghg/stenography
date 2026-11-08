import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useMicrophone } from "./useMicrophone";

// attachVoiceStream builds a WebAudio graph, which jsdom does not have. The
// envelope it feeds is not under test here — only who may prompt.
vi.mock("./useDialogue", () => ({ attachVoiceStream: async () => () => {} }));

let getUserMedia: ReturnType<typeof vi.fn>;
let trackStop: ReturnType<typeof vi.fn>;

beforeEach(() => {
  trackStop = vi.fn();
  const stream = { getTracks: () => [{ stop: trackStop }] } as unknown as MediaStream;
  getUserMedia = vi.fn(async () => stream);
  Object.defineProperty(navigator, "mediaDevices", {
    configurable: true,
    writable: true,
    value: { getUserMedia },
  });
});

afterEach(cleanup);

describe("microphone prompt", () => {
  it("asks as soon as the presenter reaches the demo checkpoint", async () => {
    const { result } = renderHook(() => useMicrophone(true));
    await waitFor(() => expect(result.current.state).toBe("on"));
    expect(getUserMedia).toHaveBeenCalledTimes(1);
  });

  it("stays silent when the room scrolled there itself", async () => {
    const { result } = renderHook(() => useMicrophone(true, false));
    await act(async () => {
      await Promise.resolve();
    });
    expect(getUserMedia).not.toHaveBeenCalled();
    expect(result.current.state).toBe("off");
  });

  it("still lets a spectator turn it on by hand", async () => {
    const { result } = renderHook(() => useMicrophone(true, false));
    await act(async () => {
      await result.current.start();
    });
    expect(getUserMedia).toHaveBeenCalledTimes(1);
    expect(result.current.state).toBe("on");
  });

  it("kills the stream when the screen is unmounted", async () => {
    const { result, unmount } = renderHook(() => useMicrophone(true));
    await waitFor(() => expect(result.current.state).toBe("on"));
    unmount();
    expect(trackStop).toHaveBeenCalled();
  });
});
