require('dotenv').config();
const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const cors = require('cors');

const app = express();
app.use(cors());
app.use(express.json());

const { GoogleGenAI } = require('@google/genai');
const ai = new GoogleGenAI({ apiKey: process.env.GEMINI_API_KEY });

const SYSTEM_INSTRUCTION = `You are Star Gazed, an AI assistant that controls an Android phone.

Given a user's intent and optional screen UI nodes, respond with a JSON object containing a list of actions to execute.

RESPOND ONLY WITH VALID JSON in this exact format:
{
  "speak": "A brief message to say aloud to the user",
  "actions": [
    { "action": "launch_app", "args": { "packageName": "com.google.android.youtube" } },
    { "action": "type_text", "args": { "text": "cats", "submit": true } }
  ]
}

Available actions:
- tap_node: args: { x, y }
- type_text: args: { text, submit (boolean) }
- swipe: args: { direction: "up|down|left|right", distance }
- press_button: args: { button: "HOME|BACK|ENTER|VOLUME_UP|VOLUME_DOWN" }
- launch_app: args: { packageName }
- open_url: args: { url }
- long_press: args: { x, y, duration }
- double_tap: args: { x, y }
- list_apps: args: {}
- set_orientation: args: { orientation: "portrait|landscape" }
- scroll: args: { direction: "up|down|left|right", distance }

Rules:
- For "search on YouTube": use launch_app("com.google.android.youtube"), then type_text with submit:true
- For "search on Chrome": use open_url("https://www.google.com/search?q=QUERY")
- For "open X app": use launch_app with correct package name
- Always include a "speak" message describing what you are doing
- Return ONLY the JSON, no markdown, no code blocks`;

async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function processGeminiRequest(intent, screenNodes, ws) {
  console.log(`[Gemini] Processing: "${intent}" | ${screenNodes.length} nodes`);

  const prompt = `User Intent: ${intent}\nScreen UI Nodes: ${JSON.stringify(screenNodes.slice(0, 20))}`;

  let response;
  // Try multiple models in sequence if quota is exhausted
  const models = ['gemini-1.5-flash', 'gemini-2.0-flash', 'gemini-2.5-flash'];
  let lastError;
  for (const model of models) {
    try {
      response = await ai.models.generateContent({
        model,
        contents: prompt,
        config: {
          systemInstruction: SYSTEM_INSTRUCTION,
          temperature: 0.1,
        }
      });
      console.log(`[Gemini] Used model: ${model}`);
      break;
    } catch (err) {
      if (err.status === 429) {
        console.log(`[Gemini] ${model} quota hit, trying next model...`);
        lastError = err;
        continue;
      }
      throw err;
    }
  }

  if (!response) {
    console.error('[Gemini] All models exhausted quota');
    ws.send(JSON.stringify({ type: 'action', action: 'speak', args: { text: 'API quota exceeded. Please wait a few minutes and try again.' } }));
    return;
  }

  let plan;
  try {
    let text = response.text.trim();
    text = text.replace(/^```json\s*/i, '').replace(/^```\s*/i, '').replace(/\s*```$/, '');
    plan = JSON.parse(text);
  } catch (e) {
    console.error('[Gemini] Failed to parse JSON plan:', response.text);
    ws.send(JSON.stringify({ type: 'action', action: 'speak', args: { text: "I couldn't understand how to do that. Please try again." } }));
    return;
  }

  if (plan.speak) {
    console.log(`[Gemini] Speaking: ${plan.speak}`);
    ws.send(JSON.stringify({ type: 'action', action: 'speak', args: { text: plan.speak } }));
  }

  const actions = plan.actions || [];
  for (const step of actions) {
    console.log(`[Gemini] Action: ${step.action}`, step.args);
    ws.send(JSON.stringify({ type: 'action', action: step.action, args: step.args || {} }));
    await sleep(1500);
  }
}

const MCP_TOOLS = [{
  functionDeclarations: [
    {
      name: "tap_node",
      description: "Taps a specific point on the screen at the given x,y coordinate.",
      parameters: {
        type: "OBJECT",
        properties: {
          x: { type: "INTEGER", description: "X coordinate to tap in pixels" },
          y: { type: "INTEGER", description: "Y coordinate to tap in pixels" },
          justification: { type: "STRING", description: "Why you are tapping here" }
        },
        required: ["x", "y"]
      }
    },
    {
      name: "speak",
      description: "Speaks text aloud to the user via text-to-speech.",
      parameters: {
        type: "OBJECT",
        properties: {
          text: { type: "STRING", description: "The text to speak" }
        },
        required: ["text"]
      }
    },
    {
      name: "swipe",
      description: "Swipe on the screen in a given direction.",
      parameters: {
        type: "OBJECT",
        properties: {
          direction: { type: "STRING", description: "Direction: up, down, left, right" },
          x: { type: "INTEGER", description: "Start X coordinate (optional, defaults to center)" },
          y: { type: "INTEGER", description: "Start Y coordinate (optional, defaults to center)" },
          distance: { type: "INTEGER", description: "Distance in pixels to swipe (optional, defaults to 400)" }
        },
        required: ["direction"]
      }
    },
    {
      name: "press_button",
      description: "Press a hardware or system button. Supported: HOME, BACK, ENTER, VOLUME_UP, VOLUME_DOWN.",
      parameters: {
        type: "OBJECT",
        properties: {
          button: { type: "STRING", description: "Button name: HOME, BACK, ENTER, VOLUME_UP, VOLUME_DOWN" }
        },
        required: ["button"]
      }
    },
    {
      name: "type_text",
      description: "Types text into the currently focused input field.",
      parameters: {
        type: "OBJECT",
        properties: {
          text: { type: "STRING", description: "Text to type" },
          submit: { type: "BOOLEAN", description: "If true, press ENTER after typing" }
        },
        required: ["text"]
      }
    },
    {
      name: "long_press",
      description: "Long press at a coordinate on the screen.",
      parameters: {
        type: "OBJECT",
        properties: {
          x: { type: "INTEGER", description: "X coordinate" },
          y: { type: "INTEGER", description: "Y coordinate" },
          duration: { type: "INTEGER", description: "Duration in milliseconds (default 800)" }
        },
        required: ["x", "y"]
      }
    },
    {
      name: "double_tap",
      description: "Double-tap on the screen at a given coordinate.",
      parameters: {
        type: "OBJECT",
        properties: {
          x: { type: "INTEGER", description: "X coordinate" },
          y: { type: "INTEGER", description: "Y coordinate" }
        },
        required: ["x", "y"]
      }
    },
    {
      name: "launch_app",
      description: "Launch an installed app by its package name.",
      parameters: {
        type: "OBJECT",
        properties: {
          packageName: { type: "STRING", description: "Android package name e.g. com.google.android.youtube" }
        },
        required: ["packageName"]
      }
    },
    {
      name: "open_url",
      description: "Open a URL in the default browser.",
      parameters: {
        type: "OBJECT",
        properties: {
          url: { type: "STRING", description: "Full URL to open e.g. https://www.google.com" }
        },
        required: ["url"]
      }
    },
    {
      name: "list_apps",
      description: "Request the Android device to return a list of all installed apps.",
      parameters: { type: "OBJECT", properties: {} }
    },
    {
      name: "take_screenshot",
      description: "Take a screenshot of the current screen and send it back.",
      parameters: { type: "OBJECT", properties: {} }
    },
    {
      name: "set_orientation",
      description: "Set the screen orientation.",
      parameters: {
        type: "OBJECT",
        properties: {
          orientation: { type: "STRING", description: "portrait or landscape" }
        },
        required: ["orientation"]
      }
    },
    {
      name: "scroll",
      description: "Scroll on the screen by swiping. Alias for swipe with a longer distance.",
      parameters: {
        type: "OBJECT",
        properties: {
          direction: { type: "STRING", description: "up or down" }
        },
        required: ["direction"]
      }
    }
  ]
}];

// Track all connected Android clients
const connectedClients = new Set();




// --- HTTP Endpoints ---
app.get('/', (req, res) => {
  res.send({ status: 'ok', service: 'Star Gazed Backend', clients: connectedClients.size });
});

app.post('/trigger', (req, res) => {
  if (connectedClients.size === 0) return res.status(503).json({ error: 'No Android clients connected' });
  const command = req.body;
  connectedClients.forEach(ws => ws.send(JSON.stringify(command)));
  res.json({ ok: true, sent: command });
});

app.post('/ask', async (req, res) => {
  const { intent } = req.body;
  if (!intent) return res.status(400).json({ error: 'intent required' });
  if (connectedClients.size === 0) return res.status(503).json({ error: 'No Android clients connected' });
  try {
    processGeminiRequest(intent, [], [...connectedClients][0]);
    res.json({ ok: true, intent });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// --- WebSocket ---
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

wss.on('connection', (ws) => {
  console.log('New Android Client connected!');
  connectedClients.add(ws);

  ws.on('message', async (message) => {
    try {
      const data = JSON.parse(message);
      if (data.type === 'request' && data.intent) {
        await processGeminiRequest(data.intent, data.screenNodes || [], ws);
      }
    } catch (e) {
      console.error('Error handling WS message', e);
      ws.send(JSON.stringify({ type: 'error', message: 'Backend error processing input' }));
    }
  });

  ws.on('close', () => {
    console.log('Android Client disconnected');
    connectedClients.delete(ws);
  });
});

const PORT = process.env.PORT || 8080;
server.listen(PORT, () => {
  console.log(`Server is listening on port ${PORT}`);
});
