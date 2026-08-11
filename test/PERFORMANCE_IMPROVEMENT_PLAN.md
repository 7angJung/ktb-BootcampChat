# 부하 상황 코드 성능 개선 우선순위 및 2인 분담

작성일: 2026-08-10

## 1. 문서 목적

이 문서는 프론트엔드와 백엔드를 기술 스택 기준으로 나누지 않는다. 한 번의 사용자 행동이 프론트엔드부터 Socket, 백엔드, MongoDB까지 어떻게 이어지는지를 기준으로 두 개의 기능 작업으로 나눈다.

따라서 담당자 한 명이 맡은 작업은 프론트엔드와 백엔드를 모두 수정할 수 있다. 담당자별 작업은 서로 다른 레이어를 따로 만드는 것이 아니라 하나의 사용자 흐름을 끝까지 완료하는 방식이다.

이 문서의 우선순위는 다음 근거를 함께 반영했다.

- 사이트와 로컬 부하테스트 결과.
- 백엔드 Prometheus 누적 스냅샷.
- 실제 Socket 이벤트 흐름.
- 프론트엔드 상태 변경과 렌더링 흐름.
- MongoDB repository 호출 구조.

현재 Task 57은 URL mismatch와 503으로 시나리오가 완료되지 않았으므로, 이를 서버 병목이 확정된 증거로 사용하지 않는다.

## 2. 현재 결과에서 확인되는 근거

### 2.1 테스트 결과의 사용 범위

| 자료 | 확인된 사실 | 판단 |
| --- | --- | --- |
| Task 51 Smoke | 1 VU, 30초, E2E 성공률 100%, 브라우저 요청 1,057건, TTFB 평균 107ms/P95 150ms, FCP 평균 341ms/P95 400ms, LCP 평균 525ms/P95 518ms | 단일 브라우저 정상 기준선으로만 사용한다. 동시 처리 한계의 근거는 아니다. |
| Task 57 | 최대 35 VU 설정, 정상 종료 0회, 오류 종료 7회, `/chat/{roomId}` URL mismatch, 503 발생 | 성능 병목으로 사용하지 않는다. 라우팅을 성능 개선 명목으로 바로 수정하지 않는다. |
| Codex 로컬 테스트 | 5명 시뮬레이션 사용자, 메시지 15건, 연결 5건, 수신 이벤트 150건, 읽음 이벤트 125건, 오류 0건 | Socket 기능 흐름 확인에는 유효하다. 용량 기준선은 아니다. |
| 로컬 측정 지연 | 평균 0.33ms, P95/P99 1ms. `socket.emit` 직후 측정 | 서버 RTT가 아니므로 성능 비교 지표에서 제외한다. |

### 2.2 백엔드 누적 모니터링 스냅샷

`apps/backend/monitoring/prometheus.txt`는 단일 부하 실행의 시계열 결과가 아니라 애플리케이션 기동 이후 누적된 스냅샷으로 보인다. 아래 수치를 특정 VU의 처리율로 해석하지는 않는다. 다만 코드의 반복 작업과 방향이 일치하므로 개선 우선순위의 보조 근거로 사용한다.

- `chatMessage` 62,201건.
- `markMessagesAsRead` 200,563건.
- 성공 메시지 처리 61,756건, 처리 시간 합계 462.20초. 단순 평균은 약 7.5ms이며 P95/P99는 없다.
- `MessageRepository.findById` 200,563건, `MessageRepository.save` 264,172건.
- `SessionRepository.findByUserId` 125,120건, `SessionRepository.save` 126,248건.
- `RateLimitRepository.findByClientId`와 `save` 각각 62,537건.
- `RoomRepository.findById` 263,712건, `UserRepository.findById` 277,158건.
- `/api/rooms` 336건, 누적 응답 시간 14.87초.

근거 파일은 [`apps/backend/monitoring/prometheus.txt`](../apps/backend/monitoring/prometheus.txt)다.

## 3. 담당자별 최종 분류와 전체 우선순위

| 순위 | 우선도 | 담당자 | 기능 단위 | 주요 결과 |
| ---: | :---: | :---: | --- | --- |
| 1 | P0 | 담당자 1 | 실시간 읽음 처리 end-to-end 배치화 | 메시지별 Socket 이벤트와 메시지별 Mongo 조회·저장을 함께 줄인다. |
| 2 | P0 | 담당자 1 | 메시지 전송 critical path 축소 | 세션, Rate Limit, 방 활동 count, 중복 broadcast를 메시지 처리 경로에서 줄인다. |
| 3 | P1 | 담당자 2 | 방 입장 REST·Socket 초기화 통합 | 방 입장 한 번에 발생하는 중복 join·room 조회·초기 조회를 줄인다. |
| 4 | P1 | 담당자 2 | 초기 메시지 N+1과 조회 인덱스 개선 | sender·file 조회를 batch화하고 방·시간 query 실행계획을 개선한다. |
| 5 | P1 | 담당자 2 | 방 목록 API와 화면 데이터 축소 | 전체 room scan, 참여자 N+1, 최근 count, 큰 payload를 줄인다. |
| 6 | P1 | 담당자 2 | 프론트 메시지 병합·정렬·렌더링 최적화 | 전체 배열 sort/map과 긴 메시지 DOM 비용을 줄인다. |
| 7 | P1 | 담당자 2 | 방 목록 polling과 상태 갱신 최적화 | health preflight, 30초 전체 조회, rooms 전체 순회를 줄인다. |
| 8 | P2 | 담당자 1 | Rate Limit·방 참가 원자성 및 특수 메시지 경로 | 동시성 경합과 AI·파일·금칙어 비용을 별도로 개선한다. |
| 9 | P2 | 담당자 1 | Socket.IO 확장성과 연결 설정 | 다중 인스턴스와 runtime 설정은 실제 확장 목표가 있을 때 진행한다. |

---

## 4. 담당자 1. 실시간 메시지·읽음·세션 파이프라인

### 4.1 담당 범위

담당자 1은 메시지가 전송되고 읽음 처리되는 전체 흐름을 맡는다. 프론트엔드 이벤트 생성부터 Socket contract, 백엔드 handler, MongoDB 저장, 방 broadcast까지 한 작업 단위로 처리한다.

주요 파일은 다음과 같다.

- `apps/frontend/components/ReadStatus.js`
- `apps/frontend/features/chat/room/roomEventHandlers.js`
- `apps/frontend/lib/socket/socketClient.js`
- `apps/backend/src/main/java/com/ktb/chatapp/websocket/socketio/handler/MessageReadHandler.java`
- `apps/backend/src/main/java/com/ktb/chatapp/service/MessageReadStatusService.java`
- `apps/backend/src/main/java/com/ktb/chatapp/websocket/socketio/handler/ChatMessageHandler.java`
- `apps/backend/src/main/java/com/ktb/chatapp/service/SessionService.java`
- `apps/backend/src/main/java/com/ktb/chatapp/service/RateLimitService.java`
- `apps/backend/src/main/java/com/ktb/chatapp/service/RoomActivityNotifier.java`
- `apps/backend/src/main/java/com/ktb/chatapp/util/BannedWordChecker.java`
- `apps/backend/src/main/java/com/ktb/chatapp/websocket/socketio/ai/AiService.java`
- `apps/backend/src/main/java/com/ktb/chatapp/config/SocketIOConfig.java`
- `apps/backend/src/main/java/com/ktb/chatapp/websocket/socketio/LocalChatDataStore.java`

### 4.2 P0. 읽음 처리를 메시지 단위에서 batch 단위로 바꾼다

#### 현재 실행 흐름

`ReadStatus.js:48`은 메시지별 `IntersectionObserver`가 화면에 들어온 순간 다음 호출을 실행한다.

```javascript
socketClient.markMessagesAsRead([messageId]);
```

`socketClient.js:151-157`은 ID 한 개를 배열에 담아 Socket 이벤트로 보낸다. 백엔드 `MessageReadStatusService.java:28-59`는 각 ID마다 `findById`와 `save`를 수행한다. 이미 읽은 메시지도 save가 조건문 밖에서 실행된다.

이 구조에서는 메시지 30개가 화면에 들어오면 프론트 이벤트가 최대 30개 생성되고, 백엔드도 메시지별 조회·저장이 발생한다. Prometheus 스냅샷의 `markMessagesAsRead` 200,563건은 이 경로를 우선 확인할 근거다.

#### 수정 방향

1. 프론트에 방 단위 `readReceiptQueue: Set<string>`를 둔다.
2. observer는 Socket 전송 대신 ID를 queue에 추가한다.
3. 100ms debounce 또는 한 프레임 단위로 queue를 flush한다.
4. 한 이벤트의 최대 `messageIds`는 50개로 제한한다.
5. 백엔드는 `$addToSet` 기반 bulk update로 한 요청의 여러 메시지를 처리한다.
6. 이미 해당 사용자가 읽은 문서는 update 대상에서 제외한다.
7. 변경된 ID만 `messagesRead`로 방에 한 번 broadcast한다.
8. `roomEventHandlers.js:37-41,112`는 `messageIds`를 Set으로 바꾼 뒤 현재 메시지에 해당하는 항목만 갱신한다.

#### 담당자 1의 완료 기준

- 메시지 30개 노출 시 Socket 이벤트가 30개가 아니라 하나 또는 소수의 batch로 발생한다.
- 같은 ID가 한 batch에 중복되지 않는다.
- 메시지 30개 읽음 처리 시 repository 호출이 30회 단위로 증가하지 않는다.
- 같은 사용자가 같은 메시지를 반복해도 reader가 중복되지 않는다.
- 서로 다른 사용자가 동시에 읽어도 reader가 유실되지 않는다.
- 이벤트 수, Mongo find/update/save 수, 읽음 반영 지연을 변경 전후 비교한다.

이 항목은 프론트만 또는 백엔드만 완료해서는 완료로 판정하지 않는다.

### 4.3 P0. 메시지 전송 critical path의 반복 DB 작업을 줄인다

`ChatMessageHandler.java:54-181`은 한 메시지마다 대략 다음 작업을 실행한다.

1. `SessionService.validateSession` 호출.
2. `RateLimitService.checkRateLimit` 호출.
3. 송신자 사용자 조회.
4. 방 조회와 참가자 확인.
5. 금칙어 검사.
6. 메시지 저장.
7. 파일 메시지이면 파일 조회.
8. 방 전체 `MESSAGE` broadcast.
9. 송신자 direct `MESSAGE` 전송.
10. 최근 메시지 count.
11. AI 멘션 처리.
12. `updateLastActivity` 호출.

#### 세션 갱신

`SessionService.java:70-131`에서 `validateSession`이 세션을 갱신해 저장한 뒤 `ChatMessageHandler.java:176`의 `updateLastActivity`가 다시 조회·저장한다.

- 검증과 activity 갱신을 하나의 atomic update로 합친다.
- 또는 activity 저장에 최소 간격을 둔다.
- TTL 연장, 세션 만료, 중복 로그인, 로그아웃, 잘못된 sessionId 의미는 유지한다.
- 인증 검증을 무기한 캐시하지 않는다.

#### Rate Limit

`RateLimitService.java:42-93`은 client ID 조회 후 count 확인과 저장을 수행한다. Prometheus 스냅샷에서 Rate Limit find/save가 각각 62,537건이다.

- 조건부 `$inc`와 upsert 또는 `findAndModify`를 검토한다.
- window 만료와 새 window 생성이 원자적으로 처리되게 한다.
- 저장 오류 시 현재 fail-open 정책을 유지할지 별도로 결정한다.

#### 방 활동 count

메시지 저장 직후 `RoomActivityNotifier`가 최근 메시지 count를 계산하면 고빈도 메시지마다 count query가 실행될 수 있다.

- 방별 count를 짧게 cache/debounce한다.
- event 기반 집계값을 두고 주기적으로 보정한다.
- 방 목록 최신성 허용 범위 안에서 비동기 처리한다.

#### 중복 메시지 전송

방 전체 broadcast가 송신자를 포함하는데도 direct `MESSAGE`를 추가로 보내는지 확인한다. 송신자 수신 횟수를 message ID별로 기록하고, 중복이면 한 전달 경로를 제거한다. 중복이 아니면 그 전달 방식을 공통 계약으로 고정한다.

### 4.4 P2. 특수 메시지와 Socket 확장 경로를 분리한다

- AI 멘션은 외부 API latency, timeout, retry, 완료 후 저장을 일반 메시지와 분리한다.
- 파일은 디스크 write, File Mongo 저장, 권한 조회를 업로드·다운로드·메시지 전송별로 분리한다.
- `BannedWordChecker`의 약 10,000개 stream 탐색은 CPU 병목으로 확인된 뒤에만 trie나 Aho-Corasick을 검토한다.
- `SocketIOConfig.java:48,62`의 `tcpNoDelay=false`, `MemoryStoreFactory`, `LocalChatDataStore`는 다중 인스턴스 목표가 있을 때만 변경한다.
- `application.properties:4-7`의 Tomcat thread·accept·connection 값은 queue와 자원 포화 확인 뒤 한 번에 하나씩 조정한다.

---

## 5. 담당자 2. 방 입장·초기 데이터·방 목록·렌더링 파이프라인

### 5.1 담당 범위

담당자 2는 사용자가 방 목록에서 방에 들어와 초기 메시지를 보고 계속 사용하는 전체 흐름을 맡는다. 프론트엔드 route·상태·렌더링과 백엔드 room·message 조회·DTO·index를 함께 수정한다.

주요 파일은 다음과 같다.

- `apps/frontend/features/chat/rooms/useRoomList.js`
- `apps/frontend/pages/chat/new.js`
- `apps/frontend/features/chat/room/useRoomHandling.js`
- `apps/frontend/features/chat/messages/useMessageList.js`
- `apps/frontend/components/ChatMessages.js`
- `apps/frontend/features/chat/room/roomEventHandlers.js`
- `apps/frontend/features/chat/rooms/ChatRoomsView.js`
- `apps/frontend/features/chat/rooms/useRoomsSocket.js`
- `apps/frontend/features/chat/rooms/RoomsTable.js`
- `apps/backend/src/main/java/com/ktb/chatapp/controller/RoomController.java`
- `apps/backend/src/main/java/com/ktb/chatapp/service/RoomService.java`
- `apps/backend/src/main/java/com/ktb/chatapp/websocket/socketio/handler/RoomJoinHandler.java`
- `apps/backend/src/main/java/com/ktb/chatapp/websocket/socketio/handler/MessageLoader.java`
- `apps/backend/src/main/java/com/ktb/chatapp/websocket/socketio/handler/MessageResponseMapper.java`
- `apps/backend/src/main/java/com/ktb/chatapp/repository/MessageRepository.java`
- `apps/backend/src/main/java/com/ktb/chatapp/model/Message.java`
- `apps/backend/src/main/java/com/ktb/chatapp/service/RecentMessageCounter.java`

### 5.2 P1. 방 입장 REST와 Socket 초기화의 중복을 제거한다

#### 현재 실행 흐름

`useRoomList.js`의 방 입장은 REST `POST /api/rooms/{roomId}/join` 후 `/chat/{roomId}`로 이동한다. `useRoomHandling.js:331-372`는 새 Socket 연결, `GET /api/rooms/{roomId}`, 이벤트 등록, Socket `joinRoom`, 초기 메시지 처리를 다시 실행한다.

`RoomJoinHandler.java:47-117`도 사용자·방 확인, 참가 처리, 시스템 메시지 저장, 초기 메시지 조회, 방 재조회, 참여자별 사용자 조회를 실행한다.

#### 확정할 수정 방향

- REST join은 비밀번호·권한 검증과 최초 participant 등록만 담당한다.
- Socket `joinRoom`은 Socket room 참가와 초기 화면 데이터를 담당한다.
- Socket join은 REST join이 처리한 participant 등록을 다시 저장하지 않는다.
- 재연결·재입장에서는 시스템 입장 메시지를 중복 생성하지 않는다.
- 프론트는 `joinRoomSuccess`로 room metadata와 초기 메시지를 받은 뒤 같은 방의 REST GET을 호출하지 않는다.
- 초기 메시지가 `joinRoomSuccess`에 포함되면 즉시 `fetchPreviousMessages`를 다시 호출하지 않는다.

### 5.3 P1. 초기 메시지 조회의 N+1과 query 비용을 줄인다

`MessageLoader.java:40-76`은 메시지 목록을 조회한 뒤 읽음 처리와 메시지별 sender 사용자 조회를 수행한다. `MessageResponseMapper`에서 파일 메시지에는 file ID 조회가 추가될 수 있다.

#### 수정 방향

- 초기 메시지의 sender ID를 모아 `findAllById`로 한 번 조회하고 Map으로 매핑한다.
- file ID도 모아 필요한 필드만 bulk 조회한다.
- 입장 시 이미 조회한 방·참가자 정보를 다시 읽지 않는다.
- 초기 메시지 응답은 서버가 chronological ascending으로 반환해 프론트의 전체 sort를 없앤다.

#### MongoDB 인덱스

`MessageRepository.java:14-25`는 방과 timestamp 기준 페이지 조회, 방과 시간 범위 count, file ID 조회를 사용한다.

- 실제 DB의 `messages.getIndexes()`를 먼저 확인한다.
- 대표 데이터로 `explain("executionStats")`를 수집한다.
- `totalKeysExamined`, `totalDocsExamined`, execution time을 비교한다.
- 필요할 때 `{ room: 1, timestamp: -1 }` 후보를 추가한다.
- 인덱스 변경 전후 insert/update write cost와 디스크 사용량을 확인한다.

`MessageRepository.java`는 담당자 2가 소유한다. 담당자 1의 읽음 bulk 구현은 이 파일을 임의로 동시에 수정하지 않고, 담당자 1 소유의 custom repository 또는 별도 bulk operation 클래스로 분리한다.

### 5.4 P1. 방 목록 전체 scan과 방별 N+1을 제거한다

`RoomService.java:34-43`은 `roomRepository.findAll()` 후 JVM에서 정렬한다. 방 응답 매핑에는 creator 조회, participant별 사용자 조회, 최근 메시지 count가 포함된다. `RoomController.java`의 생성·상세·입장 응답에도 별도 매핑이 있다.

#### 수정 방향

- MongoDB query에서 정렬과 pagination을 처리한다.
- 목록 전용 `RoomListResponse`를 만들고 참여자 전체 객체 대신 `participantCount`를 반환한다.
- creator와 participant는 ID를 모아 일괄 조회한다.
- 최근 메시지 count는 cache·aggregate·event 기반으로 관리한다.
- 서비스와 컨트롤러의 동일 응답 매핑을 하나로 통합한다.
- 프론트 `RoomsTable`은 `participants.length` 대신 `participantCount`를 사용한다.

### 5.5 P1. 프론트 메시지 병합·정렬·렌더링을 줄인다

`useMessageList.js:1-31`의 `deriveUniqueSortedMessages`는 현재와 신규 메시지를 합친 뒤 전체 정렬과 Map 재생성을 수행한다. `ChatMessages.js:66-145`도 메시지를 다시 정렬하고 전체 목록을 map한다.

#### 수정 방향

- 정상적인 최신 메시지는 전체 sort 대신 append한다.
- 과거 pagination은 앞쪽 병합 경로로 분리한다.
- 중복 검사용 Set/Map을 재사용한다.
- `ChatMessages`는 이미 정렬된 배열을 받아 두 번째 sort를 수행하지 않는다.
- 1,000개 이상 장문 방에서는 windowing 또는 virtualization을 적용한다.
- 과거 메시지 추가 시 scroll anchor와 offset을 보존한다.

### 5.6 P1. 방 목록 polling과 전체 rooms 상태 갱신을 줄인다

`ChatRoomsView.js:120-132`는 30초마다 `refreshRooms`를 실행하고, `useRoomList.js:56-61`은 rooms 조회 전에 `/api/health`를 호출한다. `useRoomsSocket`은 이미 room 생성·수정·활동 이벤트를 수신한다.

#### 수정 방향

- health check는 최초 연결과 오류 복구 때만 수행한다.
- 정상 상태에서는 Socket 이벤트를 우선 사용한다.
- polling은 재연결 보정용으로 간격을 늘리거나 변경이 없을 때 생략한다.
- room activity 업데이트를 ID 정규화 구조로 바꿔 전체 rooms 객체를 재생성하지 않는다.
- rooms가 많아질 때 `RoomsTable`을 pagination 또는 windowing한다.

---

## 6. 두 담당자가 반드시 지켜야 하는 공통 계약

이 절은 선택지가 아니다. 담당자 1과 담당자 2는 아래 정의를 기준으로 구현한다. 현재 코드가 일부 다르면 이 문서의 계약에 맞추기 위한 변경으로 취급한다.

### 6.1 식별자와 시간 형식

| 항목 | 확정 규칙 |
| --- | --- |
| room ID | 문자열 하나. 방 관련 모든 Socket payload에서 동일한 `roomId`를 사용한다. |
| message ID | Mongo ObjectId 문자열. 배열 내 중복을 허용하지 않는다. |
| user ID | 문자열. reader 비교는 `userId` 하나만 사용한다. `_id`와 `id`를 혼용하지 않는다. |
| timestamp | 서버가 생성한 UTC ISO-8601 문자열. |
| 메시지 정렬 | 서버 응답과 프론트 상태 모두 오래된 메시지부터 최신 메시지 순서다. 정상 경로에서 프론트가 전체 sort를 다시 수행하지 않는다. |

### 6.2 읽음 이벤트

#### Client → Server

이벤트 이름은 `markMessagesAsRead`로 유지한다.

```json
{
  "roomId": "room-id",
  "messageIds": ["message-id-1", "message-id-2"]
}
```

- `roomId`는 필수다.
- `messageIds`는 비어 있지 않은 문자열 배열이다.
- 한 이벤트 최대 ID 수는 50개다.
- 같은 배열 안의 중복 ID는 제거한다.
- 모든 ID는 같은 방에 속해야 한다.
- 존재하지 않는 ID는 유효한 ID 처리에 영향을 주지 않도록 무시한다.
- 같은 사용자가 같은 메시지를 반복해서 보내도 reader가 중복되지 않는다.

#### Server → Room

변경된 메시지가 하나 이상일 때만 `messagesRead`를 한 번 보낸다.

```json
{
  "roomId": "room-id",
  "messageIds": ["message-id-1", "message-id-2"],
  "userId": "user-id",
  "readAt": "2026-08-10T06:00:00Z"
}
```

- 실제로 변경된 ID만 포함한다.
- 한 batch당 broadcast는 한 번이다.
- 프론트는 `messageIds`를 Set으로 만들어 해당 메시지만 갱신한다.

현재 `socket-contract.js` 주석과 백엔드 DTO가 `roomId` 없는 payload를 사용한다면, 이 문서의 계약을 최종 기준으로 바꾼다.

### 6.3 메시지 이벤트

#### Client → Server

이벤트 이름은 `chatMessage`를 유지한다.

```json
{
  "room": "room-id",
  "type": "text",
  "content": "message-content",
  "clientMessageId": "client-generated-uuid"
}
```

- `clientMessageId`는 재시도 동안 유지되는 UUID다.
- 동일 사용자와 동일 clientMessageId는 중복 저장하지 않는다.
- 서버가 만든 `_id`와 `timestamp`가 최종 메시지 식별자와 시간이 된다.

#### Server → Client

논리적 메시지 한 건은 수신자별 `message` 이벤트 한 건으로 전달한다.

```json
{
  "_id": "message-id",
  "room": "room-id",
  "type": "text",
  "content": "message-content",
  "sender": { "id": "user-id", "name": "user-name" },
  "timestamp": "2026-08-10T06:00:00Z",
  "clientMessageId": "client-generated-uuid"
}
```

- 방 broadcast가 송신자를 포함하면 송신자 direct 전송을 추가하지 않는다.
- 송신자 제외 broadcast를 사용하면 송신자 direct 전송 한 건만 허용한다.
- `processedMessageIds`는 방어 코드이며 서버 중복 전송의 허용 근거가 아니다.

### 6.4 방 입장

#### REST

`POST /api/rooms/{roomId}/join`은 비밀번호·권한 검증과 최초 participant 등록만 담당한다.

- participant 등록은 idempotent하다.
- 이미 참가한 사용자는 participant write와 입장 시스템 메시지를 중복 생성하지 않는다.
- 최소 응답은 `{ "success": true, "data": { "roomId": "room-id" } }`다.
- 초기 메시지와 전체 participant 상세 정보는 REST 응답에 넣지 않는다.

#### Socket

`joinRoom`은 Socket room 참가와 초기 화면 데이터를 담당한다.

```json
{
  "roomId": "room-id"
}
```

`joinRoomSuccess`는 다음 구조를 사용한다.

```json
{
  "roomId": "room-id",
  "room": {
    "_id": "room-id",
    "name": "room-name",
    "hasPassword": false,
    "participantCount": 2
  },
  "participants": [{ "id": "user-id", "name": "user-name" }],
  "messages": [],
  "hasMore": false,
  "nextBefore": null
}
```

- Socket join은 REST participant 등록을 다시 저장하지 않는다.
- 재연결·재입장에서는 시스템 입장 메시지를 중복 생성하지 않는다.
- `messages`는 오래된 순서부터 최신 순서로 최대 30개다.
- 프론트는 성공 후 같은 방의 `GET /api/rooms/{roomId}`를 호출하지 않는다.
- 초기 메시지가 포함되면 즉시 `fetchPreviousMessages`를 호출하지 않는다.

### 6.5 이전 메시지 pagination

```json
{
  "roomId": "room-id",
  "limit": 30,
  "before": "oldest-message-timestamp"
}
```

응답은 다음 구조다.

```json
{
  "roomId": "room-id",
  "messages": [],
  "hasMore": true,
  "nextBefore": "older-message-timestamp"
}
```

- `limit` 기본값과 최대값은 30이다.
- 응답은 chronological ascending이다.
- 프론트는 앞쪽에 합치고 scroll anchor를 유지한다.
- `nextBefore`가 null이면 `hasMore`는 false다.

### 6.6 방 목록

`GET /api/rooms`는 요약 정보만 반환한다.

```json
{
  "success": true,
  "data": [{
    "_id": "room-id",
    "name": "room-name",
    "hasPassword": false,
    "participantCount": 2,
    "recentMessageCount": 4,
    "createdAt": "2026-08-10T06:00:00Z"
  }],
  "metadata": {
    "page": 0,
    "pageSize": 20,
    "total": 1,
    "totalPages": 1,
    "hasMore": false
  }
}
```

- participant 전체 객체와 creator 상세 객체를 넣지 않는다.
- 최신순 정렬은 서버가 보장한다.
- 프론트는 목록을 다시 정렬하지 않는다.
- `participants.length`가 아니라 `participantCount`를 사용한다.
- `roomCreated`, `roomUpdated`, `roomActivity`는 동일한 `_id`를 사용한다.

### 6.7 오류와 성능 판정

- 비즈니스 오류는 Socket `error` 이벤트의 `{ code, message }` 구조다.
- timeout은 성공으로 집계하지 않는다.
- 재시도는 `clientMessageId` 또는 roomId와 요청 목적을 기준으로 중복 처리하지 않는다.
- 정상 처리율, 오류율, timeout, duplicate event를 별도 집계한다.

## 7. 담당자별 파일 경계

### 담당자 1이 소유하는 파일

- 실시간 읽음 queue와 Socket client.
- `MessageReadHandler`, `MessageReadStatusService` 및 읽음 bulk operation 클래스.
- `ChatMessageHandler`, `SessionService`, `RateLimitService`, `RoomActivityNotifier`.
- Socket event contract와 AI·파일·금칙어·Socket scale 관련 파일.

담당자 1은 읽음 bulk 구현을 위해 담당자 2가 소유한 `MessageRepository.java`를 동시에 수정하지 않는다. 필요하면 별도 custom repository 또는 bulk operation 클래스를 만든다.

### 담당자 2가 소유하는 파일

- 방 입장 route와 `useRoomHandling`.
- `RoomController`, `RoomService`, `RoomJoinHandler`.
- `MessageLoader`, `MessageResponseMapper`, `MessageRepository`, `Message` index.
- 방 목록 DTO와 `RoomsTable`.
- `useMessageList`, `ChatMessages`, rooms polling과 Socket room list state.

## 8. 구현 순서와 완료 기준

1. 담당자 1이 읽음 payload와 batch 크기를 기준으로 프론트 queue와 백엔드 bulk update를 함께 구현한다.
2. 담당자 1이 세션·Rate Limit·활동 count·중복 broadcast를 메시지 critical path에서 분리한다.
3. 담당자 2가 방 입장 계약에 맞춰 REST join과 Socket join을 하나의 초기화 흐름으로 만든다.
4. 담당자 2가 초기 메시지 N+1, MongoDB query/index, 방 목록 DTO를 정리한다.
5. 담당자 2가 프론트 메시지 병합·정렬·렌더링과 rooms polling을 정리한다.
6. 두 담당자가 공통 계약 payload와 정렬 순서를 통합 테스트한다.

완료는 평균 지연 하나로 판단하지 않는다.

- 같은 VU, 메시지 rate, room distribution에서 P95/P99가 개선된다.
- Socket 이벤트와 MongoDB find/update/save 수가 의도한 만큼 감소한다.
- 메시지·읽음·리액션·방 입장 성공률이 유지된다.
- 브라우저 CPU, 메모리, DOM 노드 수가 악화되지 않는다.
- 세션 만료, Rate Limit, 방 권한, 동시 입장, 재연결 회귀 테스트가 통과한다.
- 공통 계약의 payload, 정렬 순서, batch 최대값이 양쪽 테스트에서 검증된다.

현재 가장 먼저 작업할 것은 담당자 1의 실시간 읽음 처리 전체다. 그 다음 담당자 1의 메시지 critical path와 담당자 2의 방 입장·초기 데이터·목록·렌더링 파이프라인을 진행한다.
