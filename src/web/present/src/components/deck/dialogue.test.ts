import { beforeEach, describe, expect, it } from "vitest";
import { getInputSnapshot, receiveDialogue, receiveTelemetry, resetDialogue } from "./dialogue";

describe("presentation input contract", () => {
  beforeEach(resetDialogue);
  it("accepts speaker ID and text as JSON", () => {
    expect(receiveDialogue('{"speaker_id":2,"text":"Проверка диалога"}')).toBe(true);
    expect(getInputSnapshot().entries[0]).toMatchObject({
      speaker_id: 2,
      text: "Проверка диалога",
    });
  });
  it("does not verify speaker identity against a roster", () => {
    expect(receiveDialogue({ speaker_id: "presenter-unknown", text: "Голос ведущего" })).toBe(true);
    expect(getInputSnapshot().entries[0]?.text).toBe("Голос ведущего");
  });
  it("rejects messages without speaker ID or text", () => {
    expect(receiveDialogue({ text: "Привет" })).toBe(false);
    expect(receiveDialogue({ speaker_id: 1 })).toBe(false);
    expect(receiveDialogue("not json")).toBe(false);
    expect(getInputSnapshot().entries).toHaveLength(0);
  });
  it("leaves unavailable telemetry blank rather than fabricating statistics", () => {
    expect(getInputSnapshot().telemetry).toEqual({ ramMb: null, cpuPercent: null });
    expect(receiveTelemetry({ ramMb: 512, cpuPercent: 24 })).toBe(true);
    expect(getInputSnapshot().telemetry).toEqual({ ramMb: 512, cpuPercent: 24 });
    expect(receiveTelemetry({ ramMb: 512, cpuPercent: 101 })).toBe(false);
    expect(receiveTelemetry({})).toBe(false);
    expect(receiveTelemetry(null)).toBe(false);
  });
});
