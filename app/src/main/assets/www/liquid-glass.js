/* ════════════════════════════════════════════════════════
   LIQUID GLASS BRIDGE
   - 글래스 "렌더링"은 네이티브(Kotlin + Compose + Kyant0 backdrop)가 담당한다.
     이 스크립트에는 SVG filter / 굴절 맵 / 스퀴클 클립 등 렌더링 코드가 없다.
   - 하는 일은 단 두 가지:
       1) 글래스가 필요한 DOM 요소의 위치·반경·터치 상태를 AndroidBridge.glassFrame 으로 보고
       2) 네이티브 슬라이딩 필에서 탭이 선택되면 기존 gv() 로직을 호출
   - app.js 의 재생/검색/AndroidBridge 통신 로직은 전혀 건드리지 않는다.
════════════════════════════════════════════════════════ */
(function () {
    "use strict";

    const B = window.AndroidBridge;
    // 네이티브 글래스가 없는 환경(브라우저 미리보기 등) → CSS 폴백 유지
    if (!B || typeof B.glassFrame !== "function") return;
    document.documentElement.classList.add("native-glass");

    // id → 요소 선택자 (표시 중일 때만 매칭)
    const TARGETS = [
        { id: "search",  sel: ".view.on .mob-srch-wrap" },
        { id: "nav",     sel: "#mob-nav" },
        { id: "bar",     sel: "#bar:not(.bar-hidden)" },
        { id: "ctx",     sel: "#pl-ctx-menu.on" },
        { id: "dialog",  sel: "#pl-dialog-overlay.on #pl-dialog" },
        { id: "confirm", sel: "#pl-confirm-overlay.on #pl-confirm" },
        { id: "add",     sel: "#pl-add-modal-overlay.on #pl-add-modal" }
    ];

    let press = null;           // { id, x, y }  (요소 로컬 좌표, CSS px)
    let last = "";
    let activeUntil = 0;
    let timer = 0, raf = 0;

    const r1 = n => Math.round(n * 10) / 10;

    function measure(t) {
        const el = document.querySelector(t.sel);
        if (!el) return null;
        const rect = el.getBoundingClientRect();
        if (rect.width < 2 || rect.height < 2) return null;
        const cs = getComputedStyle(el);
        if (cs.display === "none" || cs.visibility === "hidden" || +cs.opacity < 0.02) return null;
        // 다른 화면(전체화면 재생 등)이 덮고 있으면 글래스도 숨긴다
        const cx = Math.min(innerWidth - 1, Math.max(0, rect.left + rect.width / 2));
        const cy = Math.min(innerHeight - 1, Math.max(0, rect.top + rect.height / 2));
        const hit = document.elementFromPoint(cx, cy);
        if (!hit || !el.contains(hit)) return null;

        const item = {
            id: t.id,
            x: r1(rect.left), y: r1(rect.top), w: r1(rect.width), h: r1(rect.height),
            r: r1(Math.min(parseFloat(cs.borderTopLeftRadius) || 0, rect.width / 2, rect.height / 2))
        };
        if (press && press.id === t.id) { item.px = r1(press.x); item.py = r1(press.y); }

        if (t.id === "nav") {
            const btns = el.querySelectorAll(".mn-btn");
            if (btns.length > 1) {
                const b0 = btns[0].getBoundingClientRect();
                let idx = 0;
                btns.forEach((b, i) => { if (b.classList.contains("on")) idx = i; });
                item.tabs = btns.length; item.idx = idx;
                item.tx = r1(b0.left); item.tw = r1(b0.width);
            }
        }
        return item;
    }

    function send() {
        const items = [];
        for (const t of TARGETS) { const m = measure(t); if (m) items.push(m); }
        const json = JSON.stringify({ items: items });
        if (json !== last) { last = json; try { B.glassFrame(json); } catch (e) {} }
    }

    function loop() {
        raf = 0;
        send();
        if (performance.now() < activeUntil) raf = requestAnimationFrame(loop);
    }

    // 변화가 있을 수 있는 순간에만 rAF 로 프레임 동기화, 평소에는 저빈도 폴링
    function kick(ms) {
        activeUntil = Math.max(activeUntil, performance.now() + (ms || 700));
        if (!raf) raf = requestAnimationFrame(loop);
    }
    function idlePoll() { if (!document.hidden) send(); timer = setTimeout(idlePoll, 250); }

    // ── 터치 → 네이티브 press 효과 (InteractiveHighlight) ──
    function targetAt(node) {
        for (const t of TARGETS) {
            const el = document.querySelector(t.sel);
            if (el && el.contains(node)) return { id: t.id, el: el };
        }
        return null;
    }
    function onTouch(e) {
        const tc = e.touches[0] || e.changedTouches[0];
        if (!tc) return;
        if (e.type === "touchstart") {
            const hit = targetAt(e.target);
            if (hit) {
                const rc = hit.el.getBoundingClientRect();
                press = { id: hit.id, el: hit.el, x: tc.clientX - rc.left, y: tc.clientY - rc.top };
            }
        } else if (e.type === "touchmove" && press) {
            const rc = press.el.getBoundingClientRect();
            press.x = tc.clientX - rc.left; press.y = tc.clientY - rc.top;
        } else if (e.type === "touchend" || e.type === "touchcancel") {
            press = null;
        }
        kick(500);
    }
    ["touchstart", "touchmove", "touchend", "touchcancel"].forEach(n =>
        document.addEventListener(n, onTouch, { passive: true, capture: true }));

    // ── 네이티브 필 드래그 → 기존 내비 로직(gv) 호출 ──
    window.__glassSelectTab = function (index) {
        const btn = document.querySelectorAll("#mob-nav .mn-btn")[index];
        if (btn) btn.click();
        kick(900);
    };

    // ── 레이아웃/표시 변화 감지 ──
    // class 변화(화면 전환, 오버레이 on/off, 미니바 표시)만 감지 — 재생 진행바 등
    // 잦은 style/텍스트 갱신에는 반응하지 않아 평소 CPU 사용을 늘리지 않는다
    new MutationObserver(() => kick(900)).observe(document.body, {
        attributes: true, attributeFilter: ["class"], subtree: true
    });
    ["transitionrun", "transitionend", "animationstart", "animationend", "scroll", "resize", "orientationchange"]
        .forEach(n => addEventListener(n, () => kick(600), { passive: true, capture: true }));
    document.addEventListener("visibilitychange", () => { if (!document.hidden) kick(600); });

    kick(1500);
    idlePoll();
})();
