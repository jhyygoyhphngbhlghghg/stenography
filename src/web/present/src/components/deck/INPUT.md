# Presentation input hooks

Frontend integration only. No server receiver or real speech model is connected.
The provisional contract is `{ "speaker_id": "speaker-1", "text": "Hello" }`;
numeric IDs are also accepted. Adapt `dialogueSchema` when the final server specification arrives.

After mount, the application exposes:

```js
window.presentationInput.receiveDialogue({ speaker_id: 1, text: "Привет" });
window.presentationInput.receiveTelemetry({ ramMb: 512, cpuPercent: 24 });
await window.presentationInput.attachVoiceStream(mediaStream);
window.presentationInput.reset();
```

Alternatively dispatch `new CustomEvent('presentation:dialogue', { detail: payload })`.
Both the demo and technology read the same dialogue store and audio envelope.
Voice attachment only analyses an existing MediaStream; it does not request microphone
permission, verify speaker identities, send audio, or perform speech recognition.
The caller owns and stops stream tracks. The returned function disconnects the analyser.
Telemetry must come from the actual process; missing values show dashes.
When there is no stream, technology draws an illustrative waveform, not recorded speech.