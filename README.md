# StarGazed: The Autonomous Mobile Frontier

## Project Overview

StarGazed is an AI-powered assistant designed for autonomous mobile device control. It bridges the gap between high-level LLM reasoning and low-level Android UI interactions using an Accessibility Service and a dedicated Node.js orchestration layer.

### Features
- **Real-time UI Interaction**: High-speed gesture injection (taps, swipes, scrolls).
- **Voice Autonomous Agent**: Wake-word detection and voice-driven command execution.
- **Multimodal Intelligence**: Powered by Google Gemini (1.5 Flash and 2.0 Flash).
- **Low Latency**: Persistent WebSocket communication for fluid performance.

## Project Structure

```text
star-gazer/
├── android/   # Android client application (Kotlin)
└── backend/   # Node.js orchestration server (Google GenAI)
```

## How It Works

1. **Sense**: The Android Accessibility Service scrapes the UI tree and processes audio input.
2. **Translate**: The data is sent via WebSockets to the Node.js backend.
3. **Think**: Gemini parses the UI state and generates a sequence of actions.
4. **Act**: The backend sends JSON commands back to the device for immediate execution.

## Tech Stack
- **Android**: Kotlin, Accessibility Service, SpeechRecognizer, OkHttp.
- **Backend**: Node.js, Express, WebSockets.
- **AI**: Google Gemini API (@google/genai).
- **Environment**: Containerized with Docker, deployed on Google Cloud Run.

---
*Inspired by the vision of a truly autonomous personal assistant.*
