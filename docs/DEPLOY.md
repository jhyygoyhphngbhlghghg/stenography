# Деплой веб-презентации

Один Node-процесс держит API, WebSocket, экраны и пульт. Нужен Node 20+.

## 1. Собрать

```bash
git clone https://github.com/jhyygoyhphngbhlghghg/stenography.git
cd stenography/src/web/backend
npm ci
ADMIN_TOKEN=<длинный_секрет> npm run build:all
```

`build:all` = `tsc` для бэкенда + `npm ci` и `vite build` для `../present`.
Без второго шага `/` отдаст встроенный дек, и лог старта об этом скажет:

```
[web] deck     built-in — src/web/present is not built (npm run build there for the React deck)
```

`dist/`, `node_modules/` и `.output/` в git не попадают — на сервере сборка
своя.

## 2. Переменные

| Переменная | Значение по умолчанию | Зачем |
|---|---|---|
| `ADMIN_TOKEN` | пусто | вход в `/presenter`. Пусто → пульт отключён (сервер предупреждает в логе) |
| `PHONE_TOKEN` | пусто | `Authorization: Bearer …` для телефона: и на `POST /v1/transcript`, и на WS-подключение с `role=phone` (проверяется на upgrade). **Пусто → ingest открыт всем в сети**, для публичного хоста обязательно |
| `PORT` / `HOST` | `8080` / `0.0.0.0` | листенер |
| `PRESENT_DIR` | `src/web/present/dist/client` | откуда брать React-дек |
| `PUBLIC_DIR` | `src/web/public` | пульт и встроенный дек |
| `MAX_BODY_BYTES` | `2097152` | потолок тела у `POST /v1/transcript` |
| `PHONE_STALL_MS` | `45000` | через столько молчания экраны уходят в `fallback` |

Симулятора телефона как переменной нет — он включается командой пульта
`demo.simulate` (кнопка на `/presenter`) или отдельным процессом
`npm run sim:phone`. Роль `phone` у симулятора та же, что у настоящего
телефона, поэтому на хосте с `PHONE_TOKEN` он обязан передать токен:

```bash
PHONE_TOKEN=<он же> npm run sim:phone -- --url wss://present.example.org/v1/present-connect
# или: npm run sim:phone -- --url wss://… --token <он же>
```

Без токена соединение закрывается кодом `4001 unauthorized` — ровно как закрылся
бы телефон с неверным токеном.

## 3. systemd

```ini
# /etc/systemd/system/neurosteno-web.service
[Unit]
Description=Neuro-stenographer presentation (screens + console + API)
After=network-online.target

[Service]
Type=simple
WorkingDirectory=/opt/stenography/src/web/backend
ExecStart=/usr/bin/node dist/server.js
Environment=NODE_ENV=production
Environment=PORT=8080
Environment=HOST=127.0.0.1
EnvironmentFile=/etc/neurosteno-web.env
Restart=always
RestartSec=3
User=www-data
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/opt/stenography/src/web/backend

[Install]
WantedBy=multi-user.target
```

`/etc/neurosteno-web.env` (права `600`, в git не попадает):

```
ADMIN_TOKEN=…
PHONE_TOKEN=…
```

```bash
systemctl daemon-reload
systemctl enable --now neurosteno-web
journalctl -u neurosteno-web -f
```

## 4. Reverse proxy

Телефон подключается по `wss://`, поэтому нужен TLS — без него страница на
`https` не сможет открыть `ws://` (mixed content), и показ потеряет живую
расшифровку. Всё обслуживает один origin, поэтому прокси простой: `/` и API
езжают на 8080, заголовок Upgrade прокидывается обязательно.

```nginx
server {
    listen 443 ssl http2;
    server_name present.example.org;

    ssl_certificate     /etc/letsencrypt/live/present.example.org/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/present.example.org/privkey.pem;

    client_max_body_size 4m;      # POST /v1/transcript

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;   # cookie пульта gets Secure
        proxy_set_header Upgrade    $http_upgrade;    # WebSocket
        proxy_set_header Connection $connection_upgrade;
        proxy_read_timeout 120s;                      # пинг контракта — 15 с
    }
}

map $http_upgrade $connection_upgrade {
    default upgrade;
    '' close;
}
```

`X-Forwarded-Proto: https` важен: по нему сервер помечает cookie `presenter`
флагом `Secure` и корректно строит `ws_url` для регистрации телефона.

### Если публичный 443 занят не http-секцией nginx

На хостах, где перед nginx стоит L4 SNI-роутер (`stream { ssl_preread on; …}`),
порт 443 слушает **stream**, а http-серверы сидят на внутреннем порту с
`proxy_protocol` — обычно `127.0.0.1:4430`. Тогда `listen 443 ssl;` в
http-контексте снаружи недостижим, а клиент получает TLS alert
`unrecognized name` от роутера. Выглядит это так:

```bash
grep -rn "listen 443" /etc/nginx/stream.d/ /etc/nginx/sites-enabled/
grep -rn "map .ssl_preread_server_name" -A4 /etc/nginx/stream.d/
```

Если `default` роутера уже ведёт на 4430, трогать его карту не нужно вообще —
свой `server_name` выигрывает точное совпадение уже внутри http-секции, и
достаточно добавить к своему сайту блок по образцу соседей:

```nginx
server {
    listen 127.0.0.1:4430 ssl proxy_protocol;
    http2 on;
    server_name present.example.org;
    ssl_certificate     /etc/letsencrypt/live/present.example.org/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/present.example.org/privkey.pem;
    # real_ip обычно объявлен глобально (conf.d/*.conf):
    #   set_real_ip_from 127.0.0.1; real_ip_header proxy_protocol;
    location / { proxy_pass http://127.0.0.1:8080; /* заголовки — как в §4 */ }
}
```

Так остаются у вашего сайта и TLS-терминация, и `proxy_protocol` (то есть
реальные IP в логах), а чужие конфиги и правила файрвола не меняются. Проверять
после reload стоит не только свой URL, но и соседние: их `server_name` живёт на
том же сокете 4430.

Отдельный порт с своим cert (`listen 8443 ssl` + правило файрвола) — запасной
вариант, если трогать общий сокет нельзя вовсе; платой будет адрес вида
`https://present.example.org:8443/` в QR.

## 5. Проверка после запуска

```bash
curl -s https://present.example.org/v1/health          # {"ok":true,"slides":8,"screens":0,"rows":0}
curl -s https://present.example.org/v1/presentation | head -c 200
curl -sI https://present.example.org/ | grep -i content-type           # text/html — экраны
curl -sI https://present.example.org/presenter | grep -i content-type  # text/html — пульт
```

Дальше руками, это и есть репетиция:

1. Открыть `/presenter`, войти по `ADMIN_TOKEN`, стрелками пройти восемь
   чекпоинтов — на экране `/` должен меняться `CP 0X/08 — <имя>`.
2. `POST /v1/transcript` со строками брифа (или `npm run sim:phone`) — строки
   должны появиться на экране, а `demo_phase` стать `live`. Разница между ними
   для чести показа: `sim:phone` подделывает **само устройство** и его строки
   приходят как `worker`, то есть попадают в экспорт; серверный
   `demo.simulate` помечен `source: 'simulated'` и в экспорт не попадает.
3. Выдернуть соединение телефона → через ~45 с экраны уходят в `fallback` с
   баннером «показана запись». Это штатное поведение, а не поломка.
4. Проверить QR с чекпоинта `01`: он кодирует адрес страницы, то есть
   `https://present.example.org/`.
5. Перед тем как отдать ссылку на экспорт судьям, очистить сессию после
   репетиции: остановить `sim:phone` и `systemctl restart neurosteno-web` (или
   отправить пульту `session.rotate`). Иначе тренировочный диалог уйдёт в файл
   как результат реального прогона.

## 6. Телефон

Android-флавор `presentConnect` ходит в `PRESENT_CONNECT_WS_URL` (см.
`config/example.env`, для прода — `wss://present.example.org/v1/present-connect`)
или получает `ws_url` от `POST /v1/workers/register`. `PHONE_TOKEN` должен
совпадать с серверным, иначе ingest вернёт `401`.

Важно для защиты: сам показ офлайн-демо не зависит от сервера — распознавание
идёт на телефоне, авиарежим не ломает его. Сервер нужен только чтобы вести
экраны и показывать готовые строки залу.

## 7. Если что-то не так

| Симптом | Куда смотреть |
|---|---|
| Лог старта говорит `deck built-in` | не выполнен `build:deck`; зал увидит ванильный дек |
| Экран показывает чёрный текст вместо дека | CSP/прокси: HTML-оболочка должна идти от этого сервера, не из другого хоста |
| `wss` не открывается | нет TLS или прокси не прокидывает `Upgrade` |
| Пульт листает, экран молчит | экран — отдельный сокет: проверь `screens` в `/v1/health` и что не открыт `file://` |
| `401` на `/v1/transcript` | `PHONE_TOKEN` на телефоне и сервере разные |
| `OVER_CAPACITY` | контракт §4: больше 20 потоков, это Path B лимит |
