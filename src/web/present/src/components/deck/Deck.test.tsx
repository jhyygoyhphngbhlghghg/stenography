import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { Deck } from "./Deck";
import { CHECKPOINTS } from "./data";

// WebGL scenes cannot run in jsdom, and they are irrelevant to checkpoint
// wiring: the deck's contract is which scene is addressed, not what it draws.
vi.mock("./Scene3D", () => ({ default: () => null }));
vi.mock("./Pipeline3D", () => ({ default: () => null }));

/**
 * useBackendConnection is the seam under test. The deck must follow the
 * server's index while connected and must not try to command it — contract §1
 * makes role=screen read-only, so commands sent here would be silently lost.
 */
const connection = vi.hoisted(() => ({
  current: {
    connected: false,
    slideIndex: 0,
    slideId: "qr",
    slideLabel: "QR",
    revision: 0,
    demoPhase: "idle" as "idle" | "live" | "fallback",
    banner: null as string | null,
    transcript: [] as { speaker_id: string; text: string }[],
    phone: { connected: false, recording: false, processing: false, stub: false, engine: null },
  },
}));

vi.mock("./useBackendConnection", () => ({
  useBackendConnection: () => connection.current,
}));

// src/test/setup.ts already stubs matchMedia to "not compact", so the desktop
// branch (character splitting, ambient loops) is the one under test here.

function scenes(): HTMLElement[] {
  return Array.from(document.querySelectorAll<HTMLElement>("[data-scene]"));
}

function resetConnection() {
  connection.current = {
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
}

beforeEach(resetConnection);
afterEach(cleanup);

describe("desktop deck", () => {
  it("renders one scene and one nav item per checkpoint", () => {
    render(<Deck />);
    expect(scenes()).toHaveLength(CHECKPOINTS.length);
    expect(screen.getAllByRole("button")).toHaveLength(CHECKPOINTS.length);
  });

  it("splits heading characters so the per-letter animation has targets", async () => {
    render(<Deck />);
    await waitFor(() => {
      // Only the desktop branch calls splitChars, so .ch elements prove it ran.
      expect(document.querySelectorAll(".ch").length).toBeGreaterThan(0);
    });
  });

  it("names the checkpoint the server moved to", async () => {
    const view = render(<Deck />);
    expect(screen.getByText(/CP 01\/08/)).toBeTruthy();

    connection.current = {
      ...connection.current,
      connected: true,
      slideIndex: 5,
      slideLabel: "Технология",
    };
    view.rerender(<Deck />);

    await waitFor(() => {
      expect(screen.getByText(/CP 06\/08 — Технология/)).toBeTruthy();
    });
  });
});

describe("navigation ownership", () => {
  it("advances locally when the backend is unreachable", async () => {
    render(<Deck />);
    expect(screen.getByText(/CP 01\/08/)).toBeTruthy();

    fireEvent.keyDown(window, { key: "ArrowRight" });
    await waitFor(() => {
      expect(screen.getByText(/CP 02\/08/)).toBeTruthy();
    });
  });

  it("does not fake an advance on ArrowRight while connected", async () => {
    connection.current = {
      ...connection.current,
      connected: true,
      slideIndex: 2,
      slideLabel: "Кейс",
    };
    render(<Deck />);
    expect(screen.getByText(/CP 03\/08 — Кейс/)).toBeTruthy();

    fireEvent.keyDown(window, { key: "ArrowRight" });
    fireEvent.keyDown(window, { key: "ArrowLeft" });
    await new Promise((r) => setTimeout(r, 50));

    expect(screen.getByText(/CP 03\/08 — Кейс/)).toBeTruthy();
  });

  it("disables the nav bar while connected so control is not faked", () => {
    connection.current = { ...connection.current, connected: true };
    render(<Deck />);
    for (const button of screen.getAllByRole("button")) {
      expect(button).toBeDisabled();
    }
  });

  it("leaves the nav bar usable standalone", () => {
    render(<Deck />);
    for (const button of screen.getAllByRole("button")) {
      expect(button).toBeEnabled();
    }
  });
});

describe("live transcript surface", () => {
  it("shows backend rows and the fallback banner instead of invented text", async () => {
    connection.current = {
      ...connection.current,
      connected: true,
      slideIndex: 2,
      banner: "Показана запись, не результат модели",
      transcript: [
        { speaker_id: "speaker_1", text: "Добрый день!" },
        { speaker_id: "speaker_2", text: "Привет всем." },
      ],
    };
    render(<Deck />);

    // The same rows feed two surfaces by design: the ASR window and the
    // dialogue lanes on the demo scene, so each is asserted in its own scope.
    const box = document.querySelector("#asr-box");
    expect(box).toBeTruthy();
    const inside = within(box as HTMLElement);

    await waitFor(() => {
      expect(inside.getByText("Добрый день!")).toBeTruthy();
    });
    expect(inside.getByText("Привет всем.")).toBeTruthy();
    expect(inside.getByText("speaker_1")).toBeTruthy();
    expect(inside.getByText(/Показана запись/)).toBeTruthy();

    const demo = document.querySelector("[data-demo]");
    expect(within(demo as HTMLElement).getByText("Добрый день!")).toBeTruthy();
  });

  it("does not invent transcript text when nothing has arrived", () => {
    connection.current = { ...connection.current, connected: true, slideIndex: 2, transcript: [] };
    render(<Deck />);
    const inside = within(document.querySelector("#asr-box") as HTMLElement);
    expect(inside.getByText(/тут распознанный текст/)).toBeTruthy();
  });
});
