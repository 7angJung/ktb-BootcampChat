import http from 'k6/http';
import ws from 'k6/ws';
import { check, fail, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const API_URL = __ENV.API_URL || 'http://localhost:5001';
const SOCKET_URL = __ENV.SOCKET_URL || 'ws://localhost:5002';
const MAX_VUS = Number(__ENV.MAX_VUS || 20);
const RUN_ID = `${Date.now()}`;

const restJoinDuration = new Trend('room_rest_join_duration', true);
const socketJoinDuration = new Trend('room_socket_join_duration', true);
const entryDuration = new Trend('room_entry_e2e_duration', true);
const socketJoinSuccess = new Rate('room_socket_join_success');
const initialPayloadValid = new Rate('room_initial_payload_valid');
const roomEntryErrors = new Counter('room_entry_errors');
const socketAuthRejected = new Counter('room_socket_auth_rejected');
const socketClosedBeforeJoin = new Counter('room_socket_closed_before_join');
const socketJoinRejected = new Counter('room_socket_join_rejected');

export const options = {
  scenarios: {
    room_entry: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: __ENV.STAGE_1 || '5s', target: Math.min(5, MAX_VUS) },
        { duration: __ENV.STAGE_2 || '10s', target: Math.min(10, MAX_VUS) },
        { duration: __ENV.STAGE_3 || '10s', target: MAX_VUS },
        { duration: __ENV.STAGE_4 || '5s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
    room_socket_join_success: ['rate>0.99'],
    room_initial_payload_valid: ['rate>0.99'],
    room_rest_join_duration: ['p(95)<500'],
    room_socket_join_duration: ['p(95)<1000'],
    room_entry_e2e_duration: ['p(95)<1500'],
  },
};

function jsonHeaders(token) {
  return {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    timeout: '10s',
  };
}

function registerAndLogin(index) {
  const email = `k6-room-entry-${RUN_ID}-${index}@test.com`;
  const password = 'Test1234!';
  const name = `K6 Room Entry ${index}`;

  const registerResponse = http.post(
    `${API_URL}/api/auth/register`,
    JSON.stringify({ email, password, name }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '10s' },
  );

  if (registerResponse.status !== 201) {
    fail(`test user registration failed: status=${registerResponse.status}`);
  }

  const loginResponse = http.post(
    `${API_URL}/api/auth/login`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '10s' },
  );

  if (loginResponse.status !== 200) {
    fail(`test user login failed: status=${loginResponse.status}`);
  }

  const auth = loginResponse.json();
  if (!auth.token || !auth.sessionId) {
    fail('login response has no token or sessionId');
  }

  return { token: auth.token, sessionId: auth.sessionId };
}

export function setup() {
  const users = [];
  for (let index = 0; index <= MAX_VUS; index += 1) {
    users.push(registerAndLogin(index));
  }

  const roomResponse = http.post(
    `${API_URL}/api/rooms`,
    JSON.stringify({ name: `k6-room-entry-${RUN_ID}` }),
    jsonHeaders(users[0].token),
  );

  if (roomResponse.status !== 201) {
    fail(`room creation failed: status=${roomResponse.status}`);
  }

  const roomId = roomResponse.json('data._id');
  if (!roomId) {
    fail('room creation response has no room id');
  }

  return { users: users.slice(1), roomId };
}

export default function (data) {
  const auth = data.users[(__VU - 1) % data.users.length];
  const entryStartedAt = Date.now();
  const restStartedAt = Date.now();
  const restResponse = http.post(
    `${API_URL}/api/rooms/${data.roomId}/join`,
    JSON.stringify({ password: null }),
    jsonHeaders(auth.token),
  );
  restJoinDuration.add(Date.now() - restStartedAt);

  const restSucceeded = check(restResponse, {
    'REST join returns 200': (response) => response.status === 200,
    'REST join returns room id': (response) => response.json('data._id') === data.roomId,
  });

  if (!restSucceeded) {
    roomEntryErrors.add(1);
    return;
  }

  const socketStartedAt = Date.now();
  let joined = false;
  let payloadIsValid = false;
  const socketUrl = `${SOCKET_URL}/socket.io/?EIO=4&transport=websocket`;
  const response = ws.connect(socketUrl, {}, (socket) => {
    socket.setTimeout(() => socket.close(), 5000);
    socket.on('close', () => {
      if (!joined) {
        socketClosedBeforeJoin.add(1);
      }
    });

    socket.on('message', (rawMessage) => {
      const message = String(rawMessage);

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

      if (message.startsWith('44')) {
        socketAuthRejected.add(1);
        socket.close();
        return;
      }

      if (!message.startsWith('42')) {
        return;
      }

      let event;
      try {
        event = JSON.parse(message.slice(2));
      } catch (error) {
        return;
      }

      if (event[0] === 'joinRoomError') {
        socketJoinRejected.add(1);
        socket.close();
        return;
      }

      if (event[0] !== 'joinRoomSuccess') {
        return;
      }

      const payload = event[1];
      joined = true;
      payloadIsValid = payload?.room?._id === data.roomId
        && Array.isArray(payload?.messages)
        && Array.isArray(payload?.participants);
      socket.close();
    });
  });

  const socketSucceeded = response?.status === 101 && joined;
  socketJoinSuccess.add(socketSucceeded);
  initialPayloadValid.add(payloadIsValid);
  socketJoinDuration.add(Date.now() - socketStartedAt);
  entryDuration.add(Date.now() - entryStartedAt);

  check(null, {
    'Socket.IO upgrade succeeds': () => response?.status === 101,
    'joinRoomSuccess is received': () => joined,
    'joinRoomSuccess contains room and messages': () => payloadIsValid,
  });

  if (!socketSucceeded || !payloadIsValid) {
    roomEntryErrors.add(1);
  }

  sleep(0.5);
}
