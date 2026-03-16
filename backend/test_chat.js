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
  // If the backend drops the socket, we don't care, we just want to see the sequence of tools printed out
});

ws.on('close', () => console.log('Disconnected.'));
