import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { PhoneInfo } from "./PhoneInfo";
import { receiveTelemetry, resetDialogue } from "./dialogue";

describe("phone widget", () => {
  beforeEach(resetDialogue);
  afterEach(cleanup);

  it("shows dashes rather than invented RAM and CPU numbers", () => {
    render(<PhoneInfo visible />);
    expect(screen.getByText("RAM")).toBeTruthy();
    expect(screen.getByText("CPU")).toBeTruthy();
    // Nothing has been reported, so both values stay empty by design.
    expect(screen.getAllByText("—")).toHaveLength(2);
  });

  it("shows the numbers that were actually reported", () => {
    receiveTelemetry({ ramMb: 512, cpuPercent: 24 });
    render(<PhoneInfo visible />);
    expect(screen.getByText("512 MB")).toBeTruthy();
    expect(screen.getByText("24.0%")).toBeTruthy();
    expect(screen.queryByText("—")).toBeNull();
  });

  it("stays hidden from assistive tech when its checkpoint is off stage", () => {
    render(<PhoneInfo visible={false} />);
    expect(document.querySelector(".phone-info")?.getAttribute("aria-hidden")).toBe("true");
    expect(document.querySelector(".phone-info")?.getAttribute("data-visible")).toBe("false");
  });

  it("draws the microphone as a schematic, not a recording claim", () => {
    render(<PhoneInfo visible />);
    const svg = document.querySelector(".phone-frame");
    expect(svg?.getAttribute("role")).toBe("img");
    expect(svg?.getAttribute("aria-label")).toBe("Телефон ведущего");
    expect(document.querySelectorAll(".phone-mic rect")).toHaveLength(9);
  });
});
