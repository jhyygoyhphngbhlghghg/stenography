// Shared, non-React scroll state (read every frame by the 3D scene).
export const scrollState = { p: 0, tech: false };

/**
 * Accent of the far background for the checkpoint on stage. three.js cannot
 * read CSS custom properties, so the 3D layer takes its colour here while the
 * DOM layers (grid, ring) read `--slide-accent`, set from the same value.
 */
export const sceneAccent = { hex: "#9dff3a" };
