# 담당자 1 독립 부하테스트 결과

측정일은 2026-08-10이며, 모든 시각은 KST 기준이다.

## 1. 테스트 목적

이 문서는 담당자 1의 메시지·읽음 처리 성능 개선 전후를 같은 부하 조건에서 비교한 최종 기록이다. 프로젝트의 기존 `loadtest/`와 기존 E2E 테스트를 부하 발생기로 사용하지 않고, 임시 독립 실행기로 Socket.IO·REST·실제 프론트엔드 읽음 queue를 측정했다.

측정 대상은 다음과 같다.

- 메시지 echo 지연시간의 P50, P95, P99.
- 읽음 응답 지연시간의 P50, P95, P99.
- 메시지 전송량과 수신량으로 계산한 처리량.
- 읽음 요청 수, 읽음 이벤트 수, 평균 batch 크기.
- 중복 메시지 수신과 실행기 오류 이벤트.
- Session, Rate Limit, 방 활동 count, Message 관련 MongoDB 명령과 담당자 1 전용 Actuator counter.
- 실제 브라우저에서 프론트엔드 읽음 queue가 생성한 Socket.IO batch.

## 2. 결론 요약

이번 측정에서 가장 확실하게 확인된 개선은 중복 메시지 broadcast 제거와 읽음 batch 처리다.

- 25 VU 메시지 전용 단계에서 중복 수신이 Before `223건`에서 After `0건`으로 줄었다.
- 25 VU 읽음 집중 단계에서 읽음 P50은 `98.3ms`에서 `23.3ms`로 `76.3%` 감소했다.
- 같은 읽음 집중 단계에서 읽음 P95는 `156.4ms`에서 `60.7ms`로 `61.2%` 감소했고, P99는 `156.8ms`에서 `73.0ms`로 `53.4%` 감소했다.
- 25 VU 일반 메시지+읽음 단계의 읽음 P50은 `76.2ms`에서 `40.5ms`로 줄었지만, P95/P99는 오히려 증가했다. 읽음 경로의 평균 비용은 줄었으나 동시 tail latency 병목은 아직 남아 있다.
- 25 VU 일반 메시지 단계에서 중복은 제거됐지만 실행기 오류 이벤트가 `4건` 관측됐다. 따라서 현재 결과만으로 25 VU를 무오류 정상 한계라고 판단할 수 없다.
- 프론트엔드 실제 브라우저 시나리오에서는 읽음 packet `6건`, batch 크기 `3, 1, 2, 7, 11, 4`가 관측됐다. 관측된 메시지 ID는 `28개`이며, console error는 `0건`이다.

성능 개선이 모든 지표에서 일괄적으로 성공한 것은 아니다. 특히 25 VU `messageRead`의 읽음 P95/P99와 메시지 echo P95/P99는 후속 원인 분석이 필요하다.

## 3. 코드 변경 범위

### 3.1 읽음 처리

- 프론트엔드 `socketClient`에 room 단위 읽음 queue를 추가했다.
- queue는 100ms 뒤 flush하고, 한 요청의 최대 message ID를 50개로 제한한다.
- 컴포넌트 unmount 때 queue timer와 잔여 ID를 정리한다.
- `ReadStatus`, `UserMessage`, `FileMessage`, room event handler가 room ID를 함께 전달하고 처리하도록 맞췄다.
- 백엔드 `MessageReadHandler`가 room ID, 사용자, 참여 여부, 최대 50개 ID를 검증한다.
- `MessageReadStatusService`가 같은 방의 unread 메시지를 한 번에 조회하고 MongoDB `updateMulti`로 읽음 상태를 반영한다.
- 변경된 실제 message ID만 `messagesRead`로 broadcast한다.

관련 코드 범위는 다음과 같다.

- `apps/frontend/lib/socket/socketClient.js`
- `apps/frontend/components/ReadStatus.js`
- `apps/frontend/components/UserMessage.js`
- `apps/frontend/components/FileMessage.js`
- `apps/frontend/features/chat/room/roomEventHandlers.js`
- `apps/frontend/features/chat/room/useChatRoom.js`
- `apps/backend/src/main/java/com/ktb/chatapp/websocket/socketio/handler/MessageReadHandler.java`
- `apps/backend/src/main/java/com/ktb/chatapp/service/MessageReadStatusService.java`
- `apps/backend/src/main/java/com/ktb/chatapp/websocket/socketio/handler/MessageLoader.java`

### 3.2 메시지 중복과 세션 저장

- `clientMessageId`를 메시지 계약에 추가했다.
- 발신자와 `clientMessageId`를 이용해 재전송 중복을 조회할 수 있게 했다.
- 같은 메시지에 대해 발신자에게 직접 보내던 추가 event를 제거하고 방 broadcast만 사용하도록 했다.
- 메시지 처리 경로에서 이미 session activity를 갱신한 뒤 다시 저장하던 호출을 제거했다.

관련 코드 범위는 다음과 같다.

- `apps/backend/src/main/java/com/ktb/chatapp/websocket/socketio/handler/ChatMessageHandler.java`
- `apps/backend/src/main/java/com/ktb/chatapp/model/Message.java`
- `apps/backend/src/main/java/com/ktb/chatapp/repository/MessageRepository.java`
- `apps/backend/src/main/java/com/ktb/chatapp/service/session/SessionMongoStore.java`
- `apps/backend/src/main/java/com/ktb/chatapp/dto/ChatMessageRequest.java`
- `apps/backend/src/main/java/com/ktb/chatapp/dto/MessageResponse.java`

### 3.3 Rate Limit과 방 활동

- Rate Limit의 일반적인 read-then-save 경로를 MongoDB `findAndModify` 기반 atomic update로 바꿨다.
- 만료 document와 limit 미초과 document를 한 번의 원자적 갱신 경로로 처리한다.
- 방 활동 count는 단일 daemon executor에서 방별 250ms window로 coalescing한다.
- 해당 executor는 애플리케이션 종료 때 정리한다.

관련 코드 범위는 다음과 같다.

- `apps/backend/src/main/java/com/ktb/chatapp/service/ratelimit/RateLimitMongoStore.java`
- `apps/backend/src/main/java/com/ktb/chatapp/service/ratelimit/RateLimitStore.java`
- `apps/backend/src/main/java/com/ktb/chatapp/service/RateLimitService.java`
- `apps/backend/src/main/java/com/ktb/chatapp/service/RoomActivityNotifier.java`
- `apps/backend/src/main/java/com/ktb/chatapp/service/RecentMessageCounter.java`

### 3.4 계약과 테스트

join payload는 `{ roomId }`, 읽음 payload는 `{ roomId, messageIds }`, 메시지 payload는 `clientMessageId`를 포함하도록 통일했다. 응답의 `messagesRead`에는 `roomId`, `userId`, `messageIds`, `readAt`을 포함한다.

계약 변경은 다음 문서와 기존 검증 코드에도 반영했다.

- `apps/backend/src/main/resources/static/api/docs/socketio/asyncapi.yaml`
- `loadtest/socket-contract.js`
- `loadtest/load-test.js`
- `loadtest/ramp-up-test.js`
- 관련 백엔드·프론트엔드 단위 테스트와 통합 테스트.

## 4. 테스트 환경

### 4.1 실행 환경

- 저장소: `/Users/miles/Documents/GitHub/ktb-BootcampChat`.
- HTTP API: `http://localhost:5001`.
- Socket.IO: `http://localhost:5002`.
- 프론트엔드 개발 서버: `http://localhost:3000`.
- Backend runtime: Java `26.0.1`.
- Backend build: Maven Wrapper, Spring Boot `4.1.0`.
- Frontend: Node.js와 pnpm `10.28.2`, Next.js `16.2.9`.
- DB와 관측성: 프로젝트 dev Docker Compose의 MongoDB, Redis, Prometheus.
- 독립 Socket.IO runner: Node.js, `socket.io-client 4.8.3`, `axios 1.18.0`.
- 브라우저 runner: Playwright `1.55.0`, Chromium `140.0.7339.16`.

### 4.2 Before와 After의 코드 기준

- Before commit: `87c9841fb49dfbedc0b8d50b955f01182ddfcbba`.
- After 기준: 같은 `HEAD` commit 위에 이번 working tree의 담당자 1 변경을 적용한 상태다.
- 별도 After commit은 생성하지 않았다.

Before는 원본 커밋을 임시 detached worktree에서 실행했다. 측정 후 임시 worktree는 제거했다.

## 5. 공통 실행 조건

두 실행은 다음 조건을 동일하게 유지했다.

- 최대 계정 수와 최대 active VU: `25`.
- VU 단계: `1 → 5 → 10 → 15 → 25`.
- 마지막 단계 이외의 지속 시간: `10초`.
- 마지막 25 VU 단계의 지속 시간: `20초`.
- 메시지 간격: 사용자당 `2,000ms`.
- 읽음 queue flush 간격: `100ms`.
- 읽음 한 요청의 최대 batch: `50개`.
- 계정 등록·로그인·방 생성·방 참여 후 소켓 연결.
- 각 실행은 시작 전과 종료 후 Actuator Prometheus snapshot을 수집했다.
- 독립 runner와 동일한 Node.js 실행기를 Before와 After에 사용했다.
- 부하 실행 중 생성한 계정과 방은 별도 이름 prefix를 사용했다.

### 5.1 시나리오별 조건

| 시나리오 | 조건 | 지표 |
| --- | --- | --- |
| `messageOnly` | active VU가 2초마다 메시지를 전송한다. 읽음 요청은 만들지 않는다. | 메시지 echo, 전송·수신량, 중복, 오류 |
| `messageRead` | 메시지를 전송하고 수신한 메시지를 100ms queue로 읽음 처리한다. | 메시지 echo, 읽음 응답, 평균 batch, 중복, 오류 |
| `readBatch` | 25 VU가 연결된 뒤 30개 메시지를 seed하고 모두 같은 30개를 읽음 처리한다. | 읽음 응답, batch 크기, 중복, 오류 |
| Playwright browser | 실제 프론트 페이지에서 다른 계정이 만든 30개 메시지를 렌더링하고 `ReadStatus` IntersectionObserver를 실행한다. | 실제 WebSocket 읽음 packet, batch 크기, console error |

Before의 원본 코드는 새 room-scoped 읽음 계약과 `clientMessageId`를 지원하지 않았다. 따라서 Before runner는 논리적으로 같은 시나리오를 기존 wire shape인 `legacy`로 실행했고, After runner는 변경된 계약인 `current`로 실행했다. 이 차이는 결과 해석의 제한 사항에 다시 기록한다.

## 6. 단계별 성능 결과

latency 단위는 ms다. 각 셀의 순서는 `P50 / P95 / P99`다. 개선율은 `(After - Before) / Before × 100`이며 음수는 latency 감소다.

### 6.1 메시지 전용

| VU | Before echo | After echo | latency 변화 | Before 전송/수신/중복/오류 | After 전송/수신/중복/오류 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 18.0 / 32.4 / 32.4 | 27.2 / 65.3 / 65.3 | +51.1% / +101.2% / +101.2% | 4 / 8 / 4 / 0 | 4 / 4 / 0 / 0 |
| 5 | 17.0 / 34.9 / 34.9 | 17.4 / 32.9 / 35.4 | +2.7% / -5.7% / +1.3% | 20 / 120 / 20 / 0 | 20 / 100 / 0 / 0 |
| 10 | 22.6 / 31.5 / 39.1 | 29.0 / 38.0 / 38.9 | +28.4% / +20.9% / -0.4% | 40 / 429 / 39 / 1 | 40 / 400 / 0 / 0 |
| 15 | 26.7 / 45.5 / 53.8 | 30.4 / 36.7 / 38.0 | +13.7% / -19.2% / -29.5% | 60 / 960 / 60 / 0 | 60 / 900 / 0 / 0 |
| 25 | 44.1 / 73.0 / 147.3 | 40.6 / 144.3 / 168.9 | -8.1% / +97.7% / +14.7% | 225 / 5,798 / 223 / 2 | 225 / 5,525 / 0 / 4 |

전송 처리량은 `전송 수 / 단계 지속 시간`으로 계산한다. 25 VU 단계는 Before와 After 모두 `225 / 20 = 11.25 msg/s`를 목표로 했다. After의 수신량은 발신자 직접 중복 event가 제거돼 정상 수신 기대량에 가까워졌지만, 오류 event `4건`과 일부 미수신이 함께 관측됐다.

### 6.2 메시지와 읽음 처리

| VU | Before echo | After echo | echo 변화 | Before read ack | After read ack | read 변화 | Before 전송/수신/중복/오류 | After 전송/수신/중복/오류 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 14.2 / 22.1 / 22.1 | 20.6 / 31.6 / 31.6 | +44.6% / +43.3% / +43.3% | 10.1 / 11.6 / 11.6 | 13.5 / 23.2 / 23.2 | +33.6% / +100.0% / +100.0% | 4 / 8 / 4 / 0 | 4 / 4 / 0 / 0 |
| 5 | 13.9 / 19.4 / 19.6 | 13.5 / 21.4 / 21.5 | -3.1% / +10.1% / +9.6% | 11.0 / 23.4 / 23.8 | 10.1 / 17.2 / 17.3 | -8.3% / -26.6% / -27.1% | 20 / 120 / 20 / 0 | 20 / 100 / 0 / 0 |
| 10 | 18.7 / 21.7 / 24.2 | 27.6 / 55.8 / 61.4 | +47.6% / +156.9% / +153.8% | 25.3 / 44.5 / 45.2 | 16.6 / 46.1 / 46.6 | -34.5% / +3.6% / +3.1% | 40 / 440 / 40 / 0 | 40 / 390 / 0 / 1 |
| 15 | 22.0 / 33.9 / 36.6 | 32.4 / 38.5 / 39.1 | +47.5% / +13.3% / +6.8% | 36.5 / 49.0 / 49.8 | 23.4 / 35.9 / 36.3 | -35.7% / -26.8% / -27.1% | 60 / 944 / 59 / 1 | 60 / 900 / 0 / 0 |
| 25 | 40.0 / 88.0 / 101.1 | 44.5 / 218.3 / 231.1 | +11.1% / +148.1% / +128.7% | 76.2 / 110.9 / 131.8 | 40.5 / 276.0 / 464.2 | -46.8% / +148.9% / +252.3% | 225 / 5,798 / 223 / 2 | 225 / 5,550 / 0 / 3 |

25 VU에서 읽음 P50은 개선됐지만 tail latency가 악화됐다. 이 결과는 bulk update 자체는 빨라졌어도 동시 읽음 broadcast, Mongo 후속 조회, Socket.IO event 처리 또는 단계 종료 직전 queue drain에서 병목이 생길 수 있음을 보여준다. 현재 측정만으로 어느 한 원인을 확정하지 않는다.

### 6.3 30개 읽음 batch 집중

이 시나리오는 25 VU가 연결된 상태에서 30개 메시지를 공유하고, 각 사용자가 같은 메시지 집합을 읽는다. 실제 부하 단계의 지속 시간은 seed와 drain 시간을 포함해 약 2초로 기록됐다.

| active VU | Before echo | After echo | echo 변화 | Before read ack | After read ack | read 변화 | Before 전송/수신/중복/오류 | After 전송/수신/중복/오류 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 25 | 29.7 / 33.2 / 37.8 | 29.3 / 71.3 / 73.7 | -1.3% / +114.7% / +94.9% | 98.3 / 156.4 / 156.8 | 23.3 / 60.7 / 73.0 | -76.3% / -61.2% / -53.4% | 30 / 754 / 29 / 1 | 30 / 725 / 0 / 1 |

평균 read batch 크기는 Before `1.97개`, After `1.97개`로 동일했다. 이 실행기에서는 여러 사용자의 queue가 분리되고 수신 이벤트가 시간차로 발생하므로 30개가 항상 한 요청으로 합쳐지지 않는다. 따라서 이 결과는 단일 요청 30개 처리 성능보다 동시 batch update 경로의 성능을 측정한다.

## 7. 처리량·중복·오류 해석

### 7.1 중복 메시지

원본 Before는 발신자에게 직접 전달하는 event와 방 전체 broadcast가 동시에 발생해 중복 수신이 나타났다.

- `messageOnly`, 25 VU: 중복 `223건 → 0건`.
- `messageRead`, 25 VU: 중복 `223건 → 0건`.
- `readBatch`, 25 VU: 중복 `29건 → 0건`.

After의 중복 수신 `0건`은 이번 독립 runner가 수집한 message ID 기준 결과다. DB에 이미 존재하는 과거 메시지의 중복 여부를 의미하지 않는다.

### 7.2 오류

runner의 `errors`는 Socket.IO 연결·error event 등 실행기에서 감지한 오류 event 수다. HTTP 요청 실패율이나 모든 서버 내부 예외의 정확한 분모를 의미하지 않는다.

- Before의 오류 event는 `messageOnly`에서 VU별 `0, 0, 1, 0, 2`, `messageRead`에서 `0, 0, 0, 1, 2`, `readBatch`에서 `1`이었다.
- After의 오류 event는 `messageOnly`에서 `0, 0, 0, 0, 4`, `messageRead`에서 `0, 0, 1, 0, 3`, `readBatch`에서 `1`이었다.
- runner는 오류 개수는 기록했지만 오류 문자열·stack trace를 별도 원시 로그로 수집하지 않았다. 그러므로 이 결과에서 확정 가능한 원인은 `25 VU 단계에서 오류 event가 관측됐다`는 사실까지다.
- 단계 종료 직전의 in-flight event를 모두 기다리지 않고 150ms 뒤 종료하는 구조라서 `readRequests > readAcks`가 발생할 수 있다. 이것을 곧바로 서버 유실로 해석하지 않는다.

### 7.3 최대 정상 VU 판단

이번 측정은 25 VU까지 실행됐지만 25 VU를 무오류 한계로 증명하지 않았다. `messageOnly`와 `messageRead`에서 오류가 없는 가장 높은 단계는 After 기준 15 VU였고, 25 VU에서는 오류 event가 발생했다. 같은 조건으로 3회 이상 반복하고 오류 원인과 서버 자원 사용률을 함께 수집하기 전에는 운영 한계 VU를 확정하지 않는다.

## 8. Actuator와 MongoDB metric 비교

Prometheus 값은 각 서버 프로세스가 시작된 뒤 누적된 counter snapshot이다. 계정·방 준비 과정과 부하 단계가 함께 포함되므로 순수 부하 구간만의 delta가 아니다.

### 8.1 MongoDB command counter

| collection.command | Before | After | 변화 |
| --- | ---: | ---: | ---: |
| `messages.find` | 11,906 | 2,648 | -77.8% |
| `messages.insert` | 859 | 844 | -1.7% |
| `messages.update` | 10,595 | 1,274 | -88.0% |
| `rate_limits.find` | 728 | 51 | -93.0% |
| `rate_limits.update` | 677 | 0 | command 형태 변경 |
| `rate_limits.findAndModify` | 0 | 1,456 | atomic 경로 추가 |
| `rooms.find` | 2,316 | 2,278 | -1.6% |
| `rooms.update` | 217 | 197 | -9.2% |
| `sessions.find` | 1,599 | 878 | -45.1% |
| `sessions.update` | 1,599 | 878 | -45.1% |
| `users.find` | 7,631 | 7,580 | -0.7% |
| Socket.IO text success | 721 | 719 | -0.3% |

`messages.update`가 줄어든 것은 읽음 처리를 개별 저장 중심에서 batch `updateMulti` 중심으로 바꾼 영향과 발신자 중복 처리 제거가 함께 반영된 결과다. Before에는 `findAndModify`가 없었고 After에는 Rate Limit atomic update가 이 command로 표시되므로 두 값을 같은 종류의 write로 단순 비교하지 않는다.

### 8.2 After에서 추가한 담당자 1 전용 counter

원본 Before에는 아래 이름의 custom counter가 없었다. 따라서 아래 값은 After 실행에서 관측된 호출량이며, Before와의 직접 차이율을 계산하지 않았다.

| counter | After 값 | 의미 |
| --- | ---: | --- |
| `owner1_message_save_total` | 719 | 메시지 저장 경로 호출량 |
| `owner1_message_read_total` | 2,648 | 메시지 조회 경로 호출량 |
| `owner1_message_bulk_update_total` | 1,274 | 읽음 bulk update command 호출량 |
| `owner1_rate_limit_atomic_update_total` | 728 | Rate Limit atomic update 호출량 |
| `owner1_rate_limit_save_total` | 51 | Rate Limit 신규 저장 호출량 |
| `owner1_room_activity_count_total` | 210 | 방 활동 count 호출량 |
| `owner1_session_read_total` | 878 | Session 조회 호출량 |
| `owner1_session_save_total` | 953 | Session insert/update 저장 호출량 |

현재 counter 설계로 다음 후속 측정이 가능하다.

- `owner1_message_bulk_update_total / owner1_message_read_total`로 읽음 batch 경로의 조회 대비 update 비율 확인.
- `owner1_rate_limit_atomic_update_total`로 atomic 경로가 실제 요청에 사용됐는지 확인.
- `owner1_session_save_total`과 메시지 성공 수를 비교해 메시지당 session 저장이 다시 증가하지 않는지 확인.
- `owner1_room_activity_count_total`의 coalescing 효과를 방 활동 event 수와 함께 비교.

## 9. 브라우저 기반 프론트엔드 읽음 queue 결과

독립 Playwright runner는 seed 계정으로 방과 30개 메시지를 만든 뒤, 별도 browser 계정으로 실제 `/chat/{roomId}`를 열었다. localStorage에는 token이나 sessionId를 결과 파일에 쓰지 않고 실행 중에만 주입했다.

- 첫 실행은 Chromium 실행 파일 부재로 시작 전에 중단됐고, 임시 Playwright 의존성에 Chromium을 설치한 뒤 같은 runner를 재실행했다.
- 실행 시간: 10초.
- 실제 읽음 packet: `6건`.
- 관측 batch 크기: `3, 1, 2, 7, 11, 4`.
- 관측 읽음 ID 수: `28개`.
- batch 최대 크기: `11개`, 계약상 최대 `50개` 이내.
- 브라우저 console error: `0건`.
- 30개 중 28개만 관측된 것은 초기 viewport와 IntersectionObserver threshold 때문에 모든 seed 메시지가 화면에 동시에 노출되지 않았기 때문이다. 이를 백엔드 읽음 유실로 해석하지 않는다.

이 시나리오는 한 개의 실제 브라우저에 대한 계약·queue 동작 확인이다. 25 VU 브라우저 성능 측정이나 실제 사용자별 scroll 분포 측정은 아니다.

## 10. 발견된 병목과 후속 우선순위

### 1순위. 25 VU 읽음 경로의 tail latency

`messageRead`에서 After 읽음 P50은 개선됐지만 P95/P99는 Before보다 높았다. bulk update 이후의 후속 조회, `messagesRead` broadcast fan-out, Socket.IO event loop, 단계 종료 drain 중 어느 구간인지 분리해야 한다.

다음 측정에서 읽음 요청 수신 시각, Mongo bulk update 시작·종료 시각, 응답 broadcast 시각, 각 VU ack 시각을 같은 correlation ID 없이도 익명 sequence로 연결해 구간별 시간을 수집해야 한다. token과 sessionId는 수집하지 않는다.

### 2순위. 25 VU 메시지 echo tail latency와 오류 event

After `messageRead`의 25 VU echo P95/P99는 `88.0 / 101.1ms`에서 `218.3 / 231.1ms`로 증가했다. 중복 broadcast는 제거됐으므로 fan-out 양은 줄었지만, 다른 처리 경로의 동시성이 tail을 높였을 수 있다. 오류 event의 정확한 문자열이 없어 먼저 오류 분류 계측이 필요하다.

### 3순위. Rate Limit atomic update의 명령량과 latency 분리

After에는 read-then-save 대신 `findAndModify`가 사용됐지만 Prometheus command count만으로 latency 감소를 증명할 수 없다. atomic command의 timer P50/P95/P99와 rejected request 비율을 별도 counter/timer로 수집해야 한다.

### 4순위. Room activity coalescing 검증

rooms update counter가 `217`에서 `197`로 줄었고 After 전용 count counter는 `210`이었다. 다만 Before에는 동일 custom counter가 없고 현재 snapshot에 setup·cleanup이 포함됐다. 동일한 room에 메시지를 집중시키는 별도 시나리오로 coalescing window별 호출량을 검증해야 한다.

### 5순위. AI·파일·금칙어·Socket 확장 경로 분리 측정

이번 독립 테스트는 text message와 read path만 대상으로 했다. AI 응답, 파일 업로드, 금칙어 검사, reaction, reconnect 경로의 성능 개선 여부는 이번 결과로 판단하지 않는다.

## 11. 측정 한계

- Before는 원본 커밋의 legacy wire contract, After는 변경된 current wire contract를 사용했다. 논리 시나리오와 부하 수치는 맞췄지만 byte-level 완전 동일 조건은 아니다.
- Before와 After는 같은 로컬 Docker 서비스와 같은 개발 머신을 사용했지만, 실행 사이에 MongoDB에 기존 테스트 데이터가 남아 있었다. 매 실행별 신규 계정·방을 사용했으나 DB 전체 collection counter는 누적 상태의 영향을 받을 수 있다.
- Prometheus counter snapshot은 준비·부하·cleanup을 모두 포함한다. 순수 단계별 DB delta가 아니다.
- 25 VU 단계는 짧은 20초이며 soak 테스트가 아니다. GC, connection pool 고갈, 장시간 queue 누적은 검증하지 않았다.
- runner의 `errors`는 오류 event 수이며 HTTP 전체 요청 오류율이 아니다.
- `readRequests > readAcks`는 150ms 종료 drain으로 인한 in-flight event일 수 있다.
- 브라우저 측정은 한 개의 headless Chromium과 한 번의 10초 실행이다.
- AI, 파일, 금칙어, reaction, reconnect는 측정 범위 밖이다.

## 12. 코드 검증

성능 변경 후 저장소 코드 검증 결과는 다음과 같다.

- Backend `./mvnw test`: 성공. `210 passed, 8 skipped`.
- Backend `./mvnw package -DskipTests`: 성공.
- 변경 범위 Frontend Vitest: 성공. `5 files, 35 tests passed`.
- Frontend 전체 `pnpm test`: `30 files, 115 tests passed`까지 실행됐지만 `components/__tests__/ChatInput.test.js`에서 외부 `cdn.jsdelivr.net` DNS 오류가 unhandled error로 발생해 종료 코드 `1`이었다.
- Frontend 기본 `pnpm build`: Turbopack이 4분 이상 출력·CPU 변화 없이 대기해 중단했다.
- Frontend `pnpm exec next build --webpack`: 성공. route 생성과 production optimization을 완료했다.
- `git diff --check`: 성공.

전체 Frontend 테스트의 실패는 변경된 테스트 assertion 실패가 아니라 기존 `ChatInput` 테스트가 외부 CDN에 접근하는 과정의 DNS 오류다. 이 검증은 네트워크가 차단되지 않은 환경에서 다시 실행해야 한다.

## 13. 독립 테스트 삭제 전 검증

결과 Markdown을 완성한 뒤 다음을 확인하고 임시 경로를 삭제한다.

- Before 결과와 After 결과가 각각 존재한다.
- Before와 After 모두 `1, 5, 10, 15, 25` VU 단계 결과를 포함한다.
- `messageOnly`, `messageRead`, `readBatch` 결과가 모두 존재한다.
- 브라우저 queue 결과와 실행 실패 후 재실행 결과가 기록돼 있다.
- 오류 event 수, 확인 가능한 원인 범위, 측정 한계를 기록했다.
- Before/After latency 차이와 Actuator metric 차이를 기록했다.
- 이 Markdown에는 token, sessionId, password, Authorization 값이 포함되지 않는다.
- 삭제 대상 확인 명령으로 임시 테스트 경로 안의 파일 목록을 출력한다.
- 삭제 대상 목록에는 `test/OWNER1_LOADTEST_RESULT.md`, `test/PERFORMANCE_IMPROVEMENT_PLAN.md`, `loadtest/`, `apps/backend/`, `apps/frontend/`가 포함되지 않아야 한다.

## 테스트 코드 정리 이력

- 임시 테스트 경로: `test/owner1-loadtest/`.
- 삭제 대상: 독립 실행기, package.json, lock 파일, node_modules, raw 결과, 로그, 브라우저 trace.
- 보존 결과: `test/OWNER1_LOADTEST_RESULT.md`.
- 삭제 전 검증: 완료.
- 삭제 후 경로 확인: 완료. `test/owner1-loadtest/`가 존재하지 않고 결과 Markdown과 기존 계획 파일이 보존됐다.
