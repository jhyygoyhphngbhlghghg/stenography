import "@testing-library/jest-dom/vitest";

Object.defineProperty(window, "scrollTo", {
  writable: true,
  value: () => {},
});

// jsdom ships no ResizeObserver, and the deck observes the scene band to keep
// its fit-scale honest. Layout does not exist here, so the observer is stubbed
// rather than polyfilled: the tests assert addressing and content, not pixels.
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
Object.defineProperty(window, "ResizeObserver", {
  writable: true,
  value: ResizeObserverStub,
});

Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => {},
  }),
});
