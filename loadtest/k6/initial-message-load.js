import http from 'k6/http';
import ws from 'k6/ws';
import { check, fail, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const API_URL = __ENV.API_URL || 'http://localhost:5001';
const SOCKET_URL = __ENV.SOCKET_URL || 'ws://localhost:5002';
const VUS = Number(__ENV.VUS || 20);
const SEED_MESSAGES = Number(__ENV.SEED_MESSAGES || 30);
const RUN_ID = `${Date.now()}`;

const initialLoadDuration = new Trend('initial_message_load_duration', true);
const initialLoadSuccess = new Rate('initial_message_load_success');
const initialPayloadValid = new Rate('initial_message_payload_valid');

export const options = {
  scenarios: {
    initial_message_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: __ENV.STAGE_1 || '5s', target: Math.min(5, VUS) },
        { duration: __ENV.STAGE_2 || '10s', target: Math.min(10, VUS) },
        { duration: __ENV.STAGE_3 || '10s', target: VUS },
        { duration: __ENV.STAGE_4 || '5s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
    initial_message_load_success: ['rate>0.99'],
    initial_message_payload_valid: ['rate>0.99'],
    initial_message_load_duration: ['p(95)<1000'],
  },
};

function requestOptions(token) {
  return {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    timeout: '10s',
  };
}

function createUser(index) {
  const email = `k6-initial-message-${RUN_ID}-${index}@test.com`;
  const password = 'Test1234!';
  const register = http.post(
    `${API_URL}/api/auth/register`,
    JSON.stringify({ email, password, name: `K6 Initial Message ${index}` }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '10s' },
  );
  if (register.status !== 201) {
    fail(`registration failed: ${register.status}`);
  }

  const login = http.post(
    `${API_URL}/api/auth/login`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '10s' },
  );
  if (login.status !== 200) {
    fail(`login failed: ${login.status}`);
  }
  return { token: login.json('token'), sessionId: login.json('sessionId') };
}

function socketUrl() {
  return `${SOCKET_URL}/socket.io/?EIO=4&transport=websocket`;
}

function seedMessages(auth, roomId) {
  let seeded = 0;
  const received = {};
  const response = ws.connect(socketUrl(), {}, (socket) => {
    socket.setTimeout(() => socket.close(), 15000);
    socket.on('message', (raw) => {
      const message = String(raw);
      if (message.startsWith('0')) {
        socket.send(`40${JSON.stringify(auth)}`);
        return;
      }
      if (message.startsWith('2')) {
        socket.send('3');
        return;
      }
      if (message.startsWith('40')) {
        socket.send(`42${JSON.stringify(['joinRoom', roomId])}`);
        return;
      }
      if (!message.startsWith('42')) return;

      let event;
      try {
        event = JSON.parse(message.slice(2));
      } catch (error) {
        return;
      }
      if (event[0] === 'joinRoomSuccess') {
        for (let index = 0; index < SEED_MESSAGES; index += 1) {
          socket.send(`42${JSON.stringify(['chatMessage', {
            room: roomId,
            type: 'text',
            content: `k6-seed-${RUN_ID}-${index}`,
          }])}`);
        }
        return;
      }
      if (event[0] === 'message' && event[1]?.content?.startsWith(`k6-seed-${RUN_ID}-`)) {
        const id = event[1]._id || event[1].id;
        if (id && !received[id]) {
          received[id] = true;
          seeded += 1;
        }
        if (seeded >= SEED_MESSAGES) socket.close();
      }
    });
  });
  if (response?.status !== 101 || seeded < SEED_MESSAGES) {
    fail(`message seed failed: status=${response?.status}, seeded=${seeded}`);
  }
}

export function setup() {
  const users = [];
  for (let index = 0; index <= VUS; index += 1) {
    users.push(createUser(index));
  }

  const room = http.post(
    `${API_URL}/api/rooms`,
    JSON.stringify({ name: `k6-initial-message-${RUN_ID}` }),
    requestOptions(users[0].token),
  );
  if (room.status !== 201) fail(`room creation failed: ${room.status}`);
  const roomId = room.json('data._id');

  seedMessages(users[0], roomId);

  // 동시 participant save의 lost update와 측정을 분리하기 위해 미리 순차 등록한다.
  for (let index = 1; index < users.length; index += 1) {
    const joined = http.post(
      `${API_URL}/api/rooms/${roomId}/join`,
      JSON.stringify({ password: null }),
      requestOptions(users[index].token),
    );
    if (joined.status !== 200) fail(`pre-join failed: ${joined.status}`);
  }

  return { roomId, users: users.slice(1) };
}

export default function (data) {
  const auth = data.users[(__VU - 1) % data.users.length];
  const startedAt = Date.now();
  let succeeded = false;
  let payloadValid = false;

  const response = ws.connect(socketUrl(), {}, (socket) => {
    socket.setTimeout(() => socket.close(), 5000);
    socket.on('message', (raw) => {
      const message = String(raw);
      if (message.startsWith('0')) {
        socket.send(`40${JSON.stringify(auth)}`);
        return;
      }
      if (message.startsWith('2')) {
        socket.send('3');
        return;
      }
      if (message.startsWith('40')) {
        socket.send(`42${JSON.stringify(['joinRoom', data.roomId])}`);
        return;
      }
      if (!message.startsWith('42')) return;

      let event;
      try {
        event = JSON.parse(message.slice(2));
      } catch (error) {
        return;
      }
      if (event[0] === 'joinRoomSuccess') {
        succeeded = true;
        payloadValid = Array.isArray(event[1]?.messages)
          && event[1].messages.length >= SEED_MESSAGES;
        socket.close();
      }
      if (event[0] === 'joinRoomError') socket.close();
    });
  });

  initialLoadDuration.add(Date.now() - startedAt);
  initialLoadSuccess.add(response?.status === 101 && succeeded);
  initialPayloadValid.add(payloadValid);
  check(null, {
    'Socket.IO connection succeeds': () => response?.status === 101,
    'joinRoomSuccess is received': () => succeeded,
    'at least 30 initial messages are returned': () => payloadValid,
  });
  sleep(0.2);
}
