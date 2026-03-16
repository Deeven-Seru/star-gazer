const WebSocket = require('ws');
const ws = new WebSocket('wss://star-gazed-backend-774960018863.us-central1.run.app');

ws.on('open', () => {
  console.log('Connected to backend simulation...');
  ws.send(JSON.stringify({ 
    type: 'request', 
    intent: 'search for cats on youtube', 
    screenNodes: [] 
  }));
});

ws.on('message', (data) => {
  console.log('Backend Sent:', data.toString());
});

ws.on('close', () => console.log('Disconnected.'));
