import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { Deck } from "./Deck";
import { CHECKPOINTS, SPEAKER_COLORS } from "./data";
import { sceneAccent } from "./state";

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
    // `speaker_3` reads as `03` on a projector; identifiers are not shown.
    expect(inside.getByText("01")).toBeTruthy();
    expect(inside.getByText(/Показана запись/)).toBeTruthy();

    // One row per utterance: the demo scene no longer repeats the feed.
    expect(document.querySelectorAll(".live-feed-row")).toHaveLength(2);
    const colors = Array.from(document.querySelectorAll<HTMLElement>(".live-feed-row")).map((row) =>
      row.style.getPropertyValue("--speaker"),
    );
    expect(colors[0]).toBe(SPEAKER_COLORS[0]);
    expect(colors[1]).toBe(SPEAKER_COLORS[1]);
    expect(new Set(colors).size).toBe(2);
  });

  it("says it is quiet rather than inventing transcript text", () => {
    connection.current = { ...connection.current, connected: true, slideIndex: 2, transcript: [] };
    render(<Deck />);
    const inside = within(document.querySelector("#asr-box") as HTMLElement);
    expect(inside.getByText(/пока тишина/)).toBeTruthy();
    expect(document.querySelectorAll(".live-feed-row")).toHaveLength(0);
  });
});

describe("top bar and per-slide background", () => {
  it("puts the college mark in the top-left corner", () => {
    render(<Deck />);
    const logo = document.querySelector<HTMLImageElement>(".deck-logo");
    expect(logo).toBeTruthy();
    expect(logo?.getAttribute("src")).toBe("/college-logo.svg");
    // The audience sees whose college this is, so it is not hidden from them.
    expect(logo?.getAttribute("alt")).toBe("Логотип колледжа");
  });

  it("hands the checkpoint accent to CSS and to the 3D scene together", async () => {
    connection.current = { ...connection.current, connected: true, slideIndex: 0 };
    const view = render(<Deck />);
    const root = document.querySelector(".presentation") as HTMLElement;
    expect(root.style.getPropertyValue("--slide-accent")).toBe(CHECKPOINTS[0].accent);
    expect(sceneAccent.hex).toBe(CHECKPOINTS[0].accent);

    connection.current = { ...connection.current, connected: true, slideIndex: 4 };
    view.rerender(<Deck />);
    await waitFor(() => {
      expect(document.querySelector(".presentation")?.getAttribute("data-slide")).toBe("demo");
    });
    expect(sceneAccent.hex).toBe(CHECKPOINTS[4].accent);
  });
});

describe("console hygiene", () => {
  it("mounts without anime.js warnings about missing targets", () => {
    // anime.js logs "No target found" for an empty selector, and several
    // accents only exist on some scenes (.a-line, .qr-dot before the QR is
    // built). A noisy console during the talk is a visible defect.
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    render(<Deck />);
    const noisy = warn.mock.calls.filter((args) => String(args[0]).includes("No target found"));
    warn.mockRestore();
    expect(noisy).toEqual([]);
  });
});

describe("compact (audience phone) layout", () => {
  const desktopMatchMedia = window.matchMedia;

  afterEach(() => {
    // Without this the stub leaks: every later test in the file would silently
    // run in compact mode and assert against the wrong layout.
    Object.defineProperty(window, "matchMedia", { writable: true, value: desktopMatchMedia });
  });

  function asPhone() {
    Object.defineProperty(window, "matchMedia", {
      writable: true,
      value: (query: string) => ({
        matches: true, // max-width: 767px — a spectator opened the QR link
        media: query,
        onchange: null,
        addEventListener: () => {},
        removeEventListener: () => {},
        addListener: () => {},
        removeListener: () => {},
        dispatchEvent: () => false,
      }),
    });
  }

  it("keeps the live feed on the small screen: that is why the QR exists", async () => {
    asPhone();
    connection.current = {
      ...connection.current,
      connected: true,
      slideIndex: 4,
      transcript: [
        { speaker_id: "speaker_1", text: "Первая реплика." },
        { speaker_id: "speaker_2", text: "Вторая реплика." },
        { speaker_id: "speaker_3", text: "Третья реплика." },
      ],
    };
    render(<Deck />);
    const box = document.querySelector("#asr-box");
    expect(box).toBeTruthy();
    await waitFor(() => {
      expect(within(box as HTMLElement).getByText("Третья реплика.")).toBeTruthy();
    });
    // Two rows on a phone, not four: the screen is short.
    expect(box?.querySelectorAll(".live-feed-row").length).toBeLessThanOrEqual(2);
  });

  it("drops the heavy graphics but not the content", () => {
    asPhone();
    render(<Deck />);
    expect(document.querySelector(".phone-info")).toBeNull();
    expect(document.querySelector(".ring")).toBeNull();
    expect(document.querySelector(".grid-bg")).toBeNull();
    // The college mark is identity, not decoration: it stays.
    expect(document.querySelector(".deck-logo")).toBeTruthy();
    expect(document.querySelector("#asr-box")).toBeTruthy();
  });
});
