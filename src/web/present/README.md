# src/web/present — React-дек презентации

TanStack Start (React 19 + Vite + Tailwind 4 + anime.js 4). Восемь чекпоинтов,
совпадающих с `SLIDES` бэкенда: `qr → team → case → tester → demo → tech →
solutions → end`.

Это **пассивный экран**. Чекпоинт и фазу демо задаёт пульт ведущего
(`src/web/public/presenter.html`), дек только читает состояние — по контракту
`docs/PRESENT_CONNECT.md` §1 сокет `role=screen` read-only, поэтому дека не
отправляет команды и не делает вид, что управляет показом.

## Сборка

```bash
cd src/web/present
npm ci
npm run build          # -> dist/client (SPA-оболочка _shell.html + assets)
```

`npm run build:deck` из `src/web/backend` делает то же самое одной командой и
устанавливает зависимости по lock-файлу.

`vite build` собирает статический бандл: приложение целиком клиентское
(websocket + эффекты), серверных функций и лоадеров нет, поэтому `nitro` в
цепочке отключён (`vite.config.ts`, `nitro: false`), а `spa mode` даёт один
`_shell.html`. Сервер не нужен — файлы раздаёт бэкенд.

## Запуск в режиме разработки

```bash
npm run dev            # http://localhost:8081, бэкенд ожидается на :8080
```

В dev адрес бэкенда — `http://localhost:8080` (или `VITE_BACKEND_URL` в
`.env`). В прод-сборке дек отдаётся тем же процессом, что и API, поэтому
запрос идёт на origin страницы и `VITE_BACKEND_URL` не нужен.

## Структура

| Путь | Что |
|---|---|
| `src/components/deck/Deck.tsx` | сцена, переходы, HUD, навигация |
| `src/components/deck/data.ts` | `CHECKPOINTS`, команда, кейс, цифры `DEMO_STAGE6` |
| `src/components/deck/useBackendConnection.ts` | WS `role=screen` + HTTP-бустрап, только чтение |
| `src/components/deck/dialogue.ts` |контракт диалога (`speaker_id/text/start_ms/end_ms`) и телеметрия |
| `src/components/deck/useDialogue.ts` | `window.presentationInput` — вход диалога/голоса |
| `src/components/deck/LiveDemo.tsx` | дорожки диалога на чекпоинте «Демо» |
| `src/components/deck/Technology.tsx` | конвейер голос→модель→текст (3D на десктопе) |
| `src/components/deck/Deck.test.tsx` | принадлежность навигации, разбивка заголовков, строки транскрипта |

## Что честно не подключено

- `window.presentationInput.receiveDialogue(...)` и `attachVoiceStream(...)` —
  ручной ввод для репетиции. Реальный поток идёт с телефона через бэкенд, а не
  в обход него; микрофон сам дек не просит и не распознаёт.
- Телеметрия (`RAM/CPU`) показывает прочерк, пока значения не присланы. Числа
  не выдумываются.
- 3D-сцены выключены на узких экранах (`matchMedia (max-width: 767px)`) — там
  облегчённая схема, без WebGL-цикла.

## Тесты

```bash
npm test               # vitest: 14 тестов (дек, диалог, роутинг)
npx tsc -p tsconfig.json --noEmit
```
