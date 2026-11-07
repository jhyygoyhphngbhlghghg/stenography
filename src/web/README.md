# src/web — презентация: экраны, пульт и бэкенд

Веб-часть «Нейро-стенографиста». Контракт с телефоном —
`docs/PRESENT_CONNECT.md` (`present-connect/1`), он же описывает, чем экран
отличается от пульта.

```
телефон ведущего (микрофон + все модели)
        │  transcript.final / phone.status
        ▼
  Node-бэкенд (src/web/backend, аудио не принимает)
        │  checkpoint + transcript + slide_label
        ▼
  экраны зала (React-дек, QR) ── пульт ведущего
```

Телефон — единственный источник звука. Сервер **не принимает аудио** и не
распознаёт речь: он принимает готовые строки в схеме брифа (`speaker_id`,
`start_ms`, `end_ms`, `text`) и рассылает их экранам. Это сохраняет утверждение
«всё считается на устройстве».

## Что где лежит

| Каталог | Что | Как запустить |
|---|---|---|
| `backend/` | Node-бэкенд: WS/HTTP, чекпоинты, раздача статики | `npm install && npm run build && npm start` |
| `present/` | **React-дек** (TanStack Start) — основные экраны | `npm ci && npm run build` (или `npm run dev`) |
| `public/` | пульт ведущего + встроенный (ванильный) дек на anime.js | раздаётся бэкендом, отдельных шагов нет |

После сборки дека бэкенд раздаёт его сам, один процесс на весь показ:

| URL | кто видит |
|---|---|
| `/`, `/join`, `/stage` | React-дек (`present/dist/client/_shell.html`) |
| `/presenter` | пульт ведущего, вход по `ADMIN_TOKEN` |
| `/deck-legacy` | встроенный дек — запасной вариант, если React-сборка пропала |
| `/v1/*`, `/api/*` | API контракта |
| `ws://…/v1/present-connect?role=screen` | экраны |
| `ws://…/v1/present-connect?session=…&role=phone&device=phone_1` | телефон |

Если `present/dist/client` не собран, сервер это печатает в логе старта и
отдаёт встроенный дек — показ не падает молча.

## Запуск

```bash
cd src/web/backend
npm install
ADMIN_TOKEN=<токен_ведущего> npm run build:all   # бэкенд + дек
ADMIN_TOKEN=<токен_ведущего> npm start           # порт 8080
```

`build:all` = `build` (TypeScript) + `build:deck` (`npm ci` и `vite build` в
`present/`). Для прод-сборки `VITE_BACKEND_URL` не нужен: дек живёт на том же
origin, что и API. При `npm run dev` дека на другом порту — там адрес бэкенда
по умолчанию `http://localhost:8080`, переопределяется `VITE_BACKEND_URL`.

Для репетиции без телефона:

```bash
npm run sim:phone -- --url ws://localhost:8080/v1/present-connect
```

## Чекпоинты и скролл

Дек — восемь панелей, их `id` и русские подписи заданы в `SLIDES` /
`SLIDE_LABELS` (`backend/src/config.ts`) и должны совпадать с `CHECKPOINTS` в
`present/src/components/deck/data.ts`. Проверка этого совпадения — в тестах
(`backend/test/frontend-integration.test.ts`).

Листает **только пульт**: `checkpoint.set / step / goto`. Экран — read-only
сокет (`role=screen`), поэтому зритель физически не может уйти вперёд или
назад, а дек, подключённый к серверу, не притворяется управляющим: его стрелки
и клики по навигации отключены, пока есть соединение. Без серверадек
работает сам на себя (локальная навигация + автопереход по таймингу).

## Режимы демо

| режим | когда | что на экране |
|---|---|---|
| `live` | телефон подключён, модели на месте | реальные `transcript.final` |
| `fallback` | телефон отключился / нет моделей / вручную | запись с явным баннером |
| `idle` | демо не начато | ожидание |

Резервные строки помечены `source: 'replay'`, симулятор — `source: 'simulated'`.
Ни те ни другие **не попадают** в экспорт `/v1/sessions/{id}/transcript` — там
только четыре поля брифа и только настоящие результаты.

## HTTP API

| метод | путь | кто | назначение |
|---|---|---|---|
| GET | `/v1/presentation` | экраны | чекпоинт (опрос 1–2 Гц, контракт §5) |
| GET | `/v1/frontend/checkpoint` | React-дек | чекпоинт + `slide_label` + строки диалога |
| GET | `/v1/sessions/{id}/transcript` | судьи | экспорт в схеме брифа |
| POST | `/v1/transcript` | телефон | пуш готовых строк (`Authorization: Bearer $PHONE_TOKEN`) |
| POST | `/v1/status` | телефон | статус запись/обработка |
| POST | `/v1/workers/register` | телефон | bring-up, возвращает `ws_url` |
| GET | `/v1/health` | мониторинг | сводка |
| POST | `/api/login` | пульт | вход по `ADMIN_TOKEN`, cookie |
| POST | `/api/command` | пульт | команды чекпоинтов и демо |
| GET | `/api/invite` | пульт | ссылка + QR для зала |

WebSocket `/v1/present-connect?session&role&device` — роли `phone`, `screen`,
`presenter`. Пинг каждые 15 с (контракт §2), 45 с молчания телефона — fallback.

## Безопасность

Строгий CSP (`script-src 'self'`) действует на API, пульт и встроенный дек.
Для HTML-оболочки React-сборки политика своя: сборка отдаёт inline-бутстрап
TanStack Start и тянет веб-шрифт с Google, поэтому на этот один статический
файл разрешены `'unsafe-inline'` в `script-src` и хосты шрифтов. Данных
запроса в него не подставляется; для ассетов, консоли и API остаётся строгий
вариант.

## Тесты

```bash
cd src/web/backend && npm test      # 19 интеграционных тестов на реальном сервере
cd src/web/present && npm test      # 14 тестов дека (vitest + jsdom)
```

Бэкенд-тесты поднимают настоящий сервер: раздача дека и защита от обхода пути
(для обоих корней), приоритет React-сборки над встроенным деком, авторизация
пульта и чекпоинты, доставка транскрипта на все экраны, fallback при потере
телефона, HTTP-инжест без WebSocket, запрет «живого» режима для сборки без
моделей, CORS и `/v1/frontend/checkpoint`.

## Дизайн

Стерильный киберпанк: тёмный фон, неоновые cyan/magenta, моноширинный шрифт
для данных, линейки 1px. Палитра и типографика —
`present/src/styles.css` (экраны) и `public/css/style.css` (встроенный дек и
пульт). Анимации переходов — anime.js 4 в `present/`.
