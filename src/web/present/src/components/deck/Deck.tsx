import { lazy, Suspense, useCallback, useEffect, useRef, useState } from "react";
import { ClientOnly } from "@tanstack/react-router";
import { animate, stagger, utils } from "animejs";
import QRCode from "qrcode";
import { Transcript } from "./Transcript";
import { PhoneInfo } from "./PhoneInfo";
import { scrollState, sceneAccent } from "./state";
import {
  CASE_OWNER,
  CASE_TEXT,
  CASE_TITLE,
  CASE_TRACK,
  CHECKPOINTS,
  SOLUTIONS,
  TEAM,
  TEAM_NAME,
} from "./data";
import { LiveDemo } from "./LiveDemo";
import { Technology } from "./Technology";
import { usePresentationInput } from "./useDialogue";
import { useBackendConnection } from "./useBackendConnection";
import { Button } from "@/components/ui/button";
import { useCompactPresentation } from "./useCompactPresentation";

const Scene3D = lazy(() => import("./Scene3D"));
const TECH_IDX = CHECKPOINTS.findIndex((c) => c.id === "tech");
const DEMO_IDX = CHECKPOINTS.findIndex((c) => c.id === "demo");
const ANIMATED = ".a, .ch, .a-line, .qr-dot, .m-ch, .m-bar, .pr";
const GLYPHS = "!<>-_\\/[]{}—=+*^?#01";

function scramble(el: HTMLElement, delay: number) {
  const final = el.dataset["text"] ?? el.textContent ?? "";
  el.dataset["text"] = final;
  let frame = 0;
  const total = 22;
  setTimeout(() => {
    const id = setInterval(() => {
      frame++;
      el.textContent = final
        .split("")
        .map((c, i) =>
          c === " " || i < (frame / total) * final.length
            ? c
            : GLYPHS[Math.floor(Math.random() * GLYPHS.length)],
        )
        .join("");
      if (frame >= total) {
        clearInterval(id);
        el.textContent = final;
      }
    }, 35);
  }, delay);
}

export function Deck() {
  usePresentationInput();
  const compact = useCompactPresentation();
  const [localIdx, setLocalIdx] = useState(0);
  const backend = useBackendConnection();
  const backendRef = useRef(backend);
  backendRef.current = backend;
  const idx = backend.connected ? backend.slideIndex : localIdx;
  const [paused, setPaused] = useState(false);
  const [qr, setQr] = useState<{ size: number; cells: [number, number][] } | null>(null);
  const root = useRef<HTMLDivElement>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const bar = useRef<HTMLDivElement>(null);
  const prev = useRef<number | null>(null);
  const barAnim = useRef<ReturnType<typeof animate> | null>(null);

  // Standalone navigation only. Once the backend is connected the checkpoint
  // comes from the presenter console and this deck is a passive screen, so the
  // nav bar is disabled rather than pretending to work.
  const go = useCallback((n: number) => {
    setLocalIdx(Math.max(0, Math.min(CHECKPOINTS.length - 1, n)));
  }, []);

  useEffect(() => {
    const m = QRCode.create(window.location.origin + window.location.pathname, {
      errorCorrectionLevel: "M",
    }).modules;
    const cells: [number, number][] = [];
    for (let y = 0; y < m.size; y++)
      for (let x = 0; x < m.size; x++) if (m.get(x, y)) cells.push([x, y]);
    setQr({ size: m.size, cells });
  }, []);

  // Block manual scrolling; presenter keys only
  useEffect(() => {
    const block = (e: Event) => e.preventDefault();
    const key = (e: KeyboardEvent) => {
      if (["ArrowDown", "ArrowUp", "PageDown", "PageUp", "Home", "End"].includes(e.key)) {
        e.preventDefault();
        return;
      }
      // Connected to the backend = passive screen: the presenter console owns
      // checkpoints and timing, so keys must not fake a local advance.
      if (backendRef.current.connected) return;
      if (e.key === " ") {
        e.preventDefault();
        setPaused((p) => !p);
      } else if (e.key === "ArrowRight")
        setLocalIdx((i) => Math.min(CHECKPOINTS.length - 1, i + 1));
      else if (e.key === "ArrowLeft") setLocalIdx((i) => Math.max(0, i - 1));
    };
    window.addEventListener("wheel", block, { passive: false });
    window.addEventListener("touchmove", block, { passive: false });
    window.addEventListener("keydown", key);
    return () => {
      window.removeEventListener("wheel", block);
      window.removeEventListener("touchmove", block);
      window.removeEventListener("keydown", key);
    };
  }, []);

  // Plain scrolling: just share progress with the 3D scene and the rail
  useEffect(() => {
    const sc = scroller.current;
    if (!sc) return;
    const onScroll = () => {
      const h = sc.clientHeight || 1;
      const p = sc.scrollTop / h;
      scrollState.p = p;
      if (bar.current) root.current?.style.setProperty("--p", String(p));
    };
    const onResize = () => {
      sc.scrollTop = (prev.current ?? 0) * sc.clientHeight;
      onScroll();
    };
    sc.addEventListener("scroll", onScroll);
    window.addEventListener("resize", onResize);
    onScroll();
    return () => {
      sc.removeEventListener("scroll", onScroll);
      window.removeEventListener("resize", onResize);
    };
  }, []);

  // three.js reads its colour from shared state, not from CSS, so the scene
  // and the DOM background layers are handed the same accent per checkpoint.
  useEffect(() => {
    sceneAccent.hex = (CHECKPOINTS[idx] ?? CHECKPOINTS[0]).accent;
  }, [idx]);

  // Ambient loops. The dialogue waveforms are not here on purpose: their
  // amplitude comes from --voice-envelope, written by the phone infographic.
  useEffect(() => {
    if (compact) return;
    const a = animateNodes(document.querySelectorAll(".ring"), {
      rotate: "1turn",
      duration: 60000,
      loop: true,
      ease: "linear",
    });
    return () => {
      a?.pause();
    };
  }, [compact]);

  // Checkpoint transitions: exit FX -> real scroll -> enter FX
  useEffect(() => {
    const el = root.current;
    const sc = scroller.current;
    if (!el || !sc) return;
    const scenes = el.querySelectorAll<HTMLElement>("[data-scene]");
    if (!compact) el.querySelectorAll<HTMLElement>("h1.a, h2.a").forEach(splitChars);
    const from = prev.current;
    const next = scenes[idx];
    if (!next) return;

    scenes.forEach((s, i) => {
      if (i !== from) utils.remove(s.querySelectorAll<HTMLElement>(ANIMATED));
    });
    utils.remove(sc);

    if (from === null) {
      setNodes(el.querySelectorAll(".a, .ch"), { opacity: 0 });
      setNodes(el.querySelectorAll(".a-line"), { scaleX: 0 });
      setNodes(el.querySelectorAll(".qr-dot"), { opacity: 0 });
    }

    let lead = 0;
    if (from !== null && from !== idx) {
      const old = scenes[from];
      if (!old) return;
      utils.remove(old.querySelectorAll<HTMLElement>(ANIMATED));
      if (from === 0 && !compact) {
        animateNodes(old.querySelectorAll(".qr-dot"), {
          translateY: [
            { to: () => -2 - Math.random() * 6, duration: 500, ease: "outQuad" },
            { to: () => 40 + Math.random() * 60, duration: 900, ease: "inQuart" },
          ],
          opacity: [
            { to: 1, duration: 500 },
            { to: 0, duration: 900 },
          ],
          delay: stagger(1.2, { from: "center" }),
        });
        lead = 600;
      }
    }

    const target = idx * sc.clientHeight;
    const scrollDur = from === null ? 0 : 1900 + Math.min(3, Math.abs(idx - from) - 1) * 300;
    if (scrollDur === 0) sc.scrollTop = target;
    else animate(sc, { scrollTop: target, duration: scrollDur, delay: lead, ease: "inOutExpo" });

    const delay = lead + scrollDur * 0.5;
    animateNodes(next.querySelectorAll(".a"), {
      opacity: [0, 1],
      y: [compact ? 12 : 50, 0],
      filter: compact ? "none" : ["blur(12px)", "blur(0px)"],
      duration: compact ? 700 : 1500,
      delay: stagger(compact ? 35 : 100, { start: delay + 150 }),
      ease: "outExpo",
    });
    animateNodes(next.querySelectorAll(".ch"), {
      opacity: [0, 1],
      y: [compact ? "0.2em" : "0.8em", "0em"],
      rotateX: [compact ? 0 : -90, 0],
      filter: compact ? "none" : ["blur(8px)", "blur(0px)"],
      duration: compact ? 700 : 1400,
      delay: stagger(compact ? 0 : 20, { start: delay }),
      ease: "outExpo",
    });
    animateNodes(next.querySelectorAll(".a-line"), {
      scaleX: [0, 1],
      duration: 1600,
      delay: stagger(120, { start: delay + 300 }),
      ease: "inOutQuart",
    });
    if (!compact)
      next.querySelectorAll<HTMLElement>(".kicker").forEach((k) => scramble(k, delay + 100));
    if (idx === 0) {
      animateNodes(next.querySelectorAll(".qr-dot"), {
        opacity: [0, 1],
        translateY: compact ? 0 : [() => 20 + Math.random() * 40, 0],
        translateX: 0,
        rotate: 0,
        duration: compact ? 400 : 1100,
        delay: compact ? delay : stagger(3, { from: "center", start: delay }),
        ease: "outExpo",
      });
    }
    next.querySelectorAll<HTMLElement>("[data-count]").forEach((c) => {
      const o = { v: 0 };
      animate(o, {
        v: Number(c.dataset["count"]),
        duration: 1600,
        delay: delay + 400,
        ease: "outExpo",
        onUpdate: () => (c.textContent = String(Math.round(o.v)).padStart(2, "0")),
      });
    });

    prev.current = idx;
  }, [idx, qr, compact]);

  // Auto-advance + progress bar
  useEffect(() => {
    if (!bar.current) return;
    barAnim.current?.pause();
    const checkpoint = CHECKPOINTS[idx];
    if (!checkpoint) return;
    const dur = checkpoint.dur * 1000;
    barAnim.current = animate(bar.current, {
      width: ["0%", "100%"],
      duration: dur,
      ease: "linear",
    });
    if (paused) barAnim.current.pause();
    if (paused || idx === CHECKPOINTS.length - 1) return;
    if (backendRef.current.connected) return; // presenter controls everything
    const t = setTimeout(() => setLocalIdx((i) => i + 1), dur);
    return () => clearTimeout(t);
  }, [idx, paused]);

  const cp = CHECKPOINTS[idx] ?? CHECKPOINTS[0];

  return (
    <div
      ref={root}
      className="presentation relative h-dvh w-full overflow-hidden bg-background select-none"
      data-slide={cp.id}
      style={{ "--slide-accent": cp.accent } as React.CSSProperties}
    >
      {!compact && <div className="grid-bg pointer-events-none absolute inset-0" />}
      {!compact && (
        <ClientOnly fallback={null}>
          <Suspense fallback={null}>
            <Scene3D quiet={idx === TECH_IDX || idx === TECH_IDX - 1} />
          </Suspense>
        </ClientOnly>
      )}
      {!compact && (
        <svg
          className="ring pointer-events-none absolute left-1/2 top-1/2 -ml-[45vmin] -mt-[45vmin] h-[90vmin] w-[90vmin] opacity-20"
          viewBox="0 0 200 200"
        >
          <circle
            cx="100"
            cy="100"
            r="98"
            fill="none"
            stroke="currentColor"
            strokeWidth=".2"
            strokeDasharray="1 3"
          />
          <circle
            cx="100"
            cy="100"
            r="80"
            fill="none"
            stroke="currentColor"
            strokeWidth=".15"
            strokeDasharray="20 6 2 6"
          />
        </svg>
      )}

      {/* scroll position rail */}
      <div className="hidden md:block fixed right-6 top-1/2 z-40 h-40 w-px -translate-y-1/2 bg-border">
        <div
          className="absolute left-1/2 h-6 w-[3px] -translate-x-1/2 bg-neon glow"
          style={{ top: `calc(var(--p, 0) / ${CHECKPOINTS.length - 1} * (100% - 1.5rem))` }}
        />
      </div>

      {/*
        Верхняя панель: знак колледжа, подпись команды, живая лента, телефон.
        Слева направо — в том порядке, в котором ведущий читает экран, поэтому
        ни один из четырёх элементов не прячется в углу.
      */}
      <header className="deck-top">
        <img
          src="/college-logo.svg"
          alt="Логотип колледжа"
          className="deck-logo"
          width={54}
          height={30}
        />
        <div className="presentation-hud font-mono">
          <div className="flex items-center gap-2 text-neon">
            <span
              className={`h-1.5 w-1.5 rounded-full ${backend.connected ? "bg-neon glow" : "bg-red-500"}`}
            />
            {TEAM_NAME}
          </div>
          <div className="mt-1">
            CP {String(idx + 1).padStart(2, "0")}/{String(CHECKPOINTS.length).padStart(2, "0")} —{" "}
            {backend.connected ? backend.slideLabel : cp.label}
          </div>
          {backend.banner && <div className="mt-1 text-amber-400">{backend.banner}</div>}
          {paused && <div className="mt-1 text-foreground">⏸ paused</div>}
        </div>
        {/*
          Лента живёт и на телефоне зрителя: заходить по QR ради одного только
          заголовка слайда смысла нет —recognised реплики и есть продукт.
          Экономия на мобильном идёт за счёт тяжёлой графики (3D, кольцо,
          схема телефона), а не за счёт содержимого.
        */}
        <Transcript
          visible={idx > 0 && idx <= TECH_IDX}
          entries={backend.transcript}
          banner={backend.banner}
          rows={compact ? 1 : 4}
        />
        {!compact && <PhoneInfo visible={idx === DEMO_IDX || idx === TECH_IDX} />}
      </header>

      <div className="presentation-nav fixed inset-x-6 bottom-6 z-40 flex gap-2">
        {CHECKPOINTS.map((c, i) => (
          <Button
            variant="ghost"
            key={c.id}
            onClick={() => go(i)}
            disabled={backend.connected}
            title={backend.connected ? "Чекпоинт ведёт пульт ведущего" : c.label}
            className="group block h-auto min-w-0 flex-1 rounded-none p-0 text-left hover:bg-transparent"
            aria-label={c.label}
          >
            <div className="relative h-px w-full bg-border">
              {i < idx && <div className="absolute inset-0 bg-neon" />}
              {i === idx && <div ref={bar} className="absolute inset-y-0 left-0 bg-neon glow" />}
            </div>
            <div
              className={`mt-2 font-mono text-[10px] uppercase tracking-[0.2em] ${i === idx ? "text-neon" : "text-muted-foreground group-hover:text-foreground"}`}
            >
              {String(i + 1).padStart(2, "0")} <span className="hidden md:inline">{c.label}</span>
            </div>
          </Button>
        ))}
      </div>

      <div ref={scroller} className="relative z-10 h-full w-full overflow-hidden">
        {/* 01 QR */}
        <Scene>
          <div className="flex flex-col items-center gap-8">
            <p className="a kicker">// scan to follow along</p>
            {qr && (
              <svg
                viewBox={`-2 -2 ${qr.size + 4} ${qr.size + 4}`}
                className="h-[52vmin] w-[52vmin]"
              >
                {qr.cells.map(([x, y]) => (
                  <rect
                    key={`${x}-${y}`}
                    className="qr-dot"
                    x={x + 0.08}
                    y={y + 0.08}
                    width={0.84}
                    height={0.84}
                    rx={0.15}
                    fill="var(--neon)"
                  />
                ))}
              </svg>
            )}
            <h1 className="a text-4xl font-medium tracking-tight">{TEAM_NAME}</h1>
          </div>
        </Scene>

        {/* 02 Team */}
        <Scene>
          <div className="w-full max-w-5xl">
            <p className="a kicker">// 01 — команда</p>
            <h2 className="a mt-4 text-6xl font-medium tracking-tight">
              Наша <span className="text-neon text-glow">команда</span>
            </h2>
            <div className="a-line mt-10 h-px origin-left bg-neon/50" />
            <ul className="team-list">
              {TEAM.map((m, i) => (
                <li
                  key={m.name}
                  className="a flex items-baseline justify-between border-b border-border py-5"
                >
                  <span className="font-mono text-sm text-neon">
                    {String(i + 1).padStart(2, "0")}
                  </span>
                  <span className="flex-1 pl-8 text-3xl">{m.name}</span>
                  <span className="font-mono text-sm uppercase tracking-widest text-muted-foreground">
                    {m.role}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        </Scene>

        {/* 03 Case */}
        <Scene>
          <div className="max-w-4xl">
            <p className="a kicker">// 02 — кейс</p>
            <h2 className="a mt-6 text-7xl font-medium leading-[1.05] tracking-tight">
              {CASE_TITLE}
            </h2>
            <p className="a mt-4 font-mono text-sm uppercase tracking-[0.25em] text-neon">
              {CASE_OWNER} · {CASE_TRACK}
            </p>
            <div className="a-line my-10 h-px w-40 origin-left bg-neon" />
            <p className="a max-w-2xl text-2xl leading-relaxed text-muted-foreground">
              {CASE_TEXT}
            </p>
          </div>
        </Scene>

        {/* 04 Tester — только текст: инфографика здесь была декорацией. */}
        <Scene>
          <div className="max-w-4xl text-center">
            <p className="a kicker">// 03 — нужна помощь</p>
            <h2 className="a mx-auto mt-6 max-w-5xl text-6xl font-medium leading-[1.08] tracking-tight">
              Для демонстрации потребуются{" "}
              <span className="text-neon text-glow">люди из аудитории</span>
            </h2>
          </div>
        </Scene>

        {/* 05 Demo */}
        <Scene>
          <LiveDemo
            compact={compact}
            active={idx === DEMO_IDX}
            backendEntries={backend.transcript}
          />
        </Scene>

        {/* 06 Tech */}
        <Scene>
          <Technology compact={compact} active={idx === TECH_IDX} warm={idx >= TECH_IDX - 1} />
        </Scene>

        {/* 07 Solutions */}
        <Scene>
          <div className="w-full max-w-6xl">
            <p className="a kicker">// 06 — решения</p>
            <h2 className="a mt-6 text-6xl font-medium tracking-tight">
              <span className="text-neon text-glow">Решения</span>
            </h2>
            <div className="solutions-list mt-14 grid grid-cols-3 gap-6">
              {SOLUTIONS.map((s, i) => (
                <div key={s.t} className="a border border-border bg-card/50 p-8">
                  <div className="font-mono text-sm text-neon">
                    <span data-count={i + 1}>00</span>
                  </div>
                  <h3 className="mt-8 text-2xl">{s.t}</h3>
                  <p className="mt-3 text-muted-foreground">{s.d}</p>
                  <div className="a-line my-6 h-px origin-left bg-neon/50" />
                  <div className="font-mono text-4xl text-neon text-glow">{s.m}</div>
                </div>
              ))}
            </div>
          </div>
        </Scene>

        {/* 08 End */}
        <Scene>
          <div className="text-center">
            <p className="a kicker">// eof</p>
            <h2 className="a mt-6 text-6xl md:text-8xl font-medium leading-none tracking-tighter">
              Спасибо за внимание<span className="text-neon caret">_</span>
            </h2>
            <p className="a mt-8 font-mono text-muted-foreground">{TEAM_NAME}</p>
          </div>
        </Scene>
      </div>
    </div>
  );
}

/**
 * anime.js logs "No target found" for an empty selector, and several accents
 * exist only on some scenes (.a-line, .qr-dot). On stage the console must stay
 * clean, so a missing target is skipped instead of animated.
 */
function animateNodes(nodes: NodeListOf<Element>, params: Parameters<typeof animate>[1]) {
  if (nodes.length === 0) return null;
  return animate(nodes, params);
}

/** Same rule for the initial state: `utils.set` warns on an empty target too. */
function setNodes(nodes: NodeListOf<Element>, params: Parameters<typeof utils.set>[1]) {
  if (nodes.length === 0) return;
  utils.set(nodes, params);
}

function splitChars(h: HTMLElement) {
  if (h.dataset["split"]) return;
  h.dataset["split"] = "1";
  h.classList.remove("a");
  h.style.opacity = "1";
  const walk = (node: Node) => {
    Array.from(node.childNodes).forEach((n) => {
      if (n.nodeType === 3) {
        const frag = document.createDocumentFragment();
        (n.textContent ?? "").split(/(\s+)/).forEach((w) => {
          if (!w) return;
          if (/^\s+$/.test(w)) {
            frag.appendChild(document.createTextNode(w));
            return;
          }
          const word = document.createElement("span");
          word.className = "inline-block whitespace-nowrap";
          for (const c of w) {
            const ch = document.createElement("span");
            ch.className = "ch inline-block opacity-0";
            ch.textContent = c;
            word.appendChild(ch);
          }
          frag.appendChild(word);
        });
        n.replaceWith(frag);
      } else walk(n);
    });
  };
  walk(h);
}

function Scene({ children }: { children: React.ReactNode }) {
  return (
    <section
      data-scene
      className="relative flex h-dvh w-full items-center justify-center px-6 pb-20 pt-24 md:px-16 md:[perspective:1000px]"
    >
      <div data-inner className="flex min-w-0 w-full items-center justify-center">
        {children}
      </div>
    </section>
  );
}
