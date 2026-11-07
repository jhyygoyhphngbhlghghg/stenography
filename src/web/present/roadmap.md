# Tasks

- [x] Replace phone demo with dialogue timeline and telemetry placeholders.
- [x] Add shared JSON input and voice hooks, with contract tests.
- [x] Animate technology as a 3D voice/model/transcription pipeline.
- [x] Verify demo, technology, and transition to solutions.
- [x] Connect the real server feed. `useBackendConnection` reads
  `GET /v1/frontend/checkpoint` and the `role=screen` WebSocket of
  `src/web/backend`; the checkpoint shown is the console's, transcript rows
  come from the phone. Verified: presenter → screen follows all 8 checkpoints,
  pushed rows reach both transcript surfaces, losing the phone shows the
  flagged fallback.
- [x] Fill solutions statistics with measured figures — `data.ts` carries
  WER 0.6471 and RTF 0.1173 from `DEMO_STAGE6_impl_hardcap6`
  (`docs/TEST_RESULTS.md`, `artifacts/eval/demo-stress/`).
- [ ] Verify the small-screen layout by eye on a real phone (compact mode is
  built and renders without WebGL, and its structure is covered by tests, but
  nobody has looked at it on a device).
- [ ] Wire a live microphone stream into `attachVoiceStream` if the stage
  script ever needs in-browser voice instead of the phone. Deliberately not
  done: the phone is the only audio source, so the mic would compete with the
  claim that recognition happens on the device.
