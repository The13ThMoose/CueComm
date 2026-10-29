/* The page is a session: scroll position is the playhead, the sections are flags on the lanes. Everything here is an
   enhancement: without it the page reads and navigates as plain HTML. */
(() => {
  'use strict';
  const doc = document.documentElement, body = document.body;
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
  const HEAD = 0.035, BARS = 96, SECS_PER_BAR = 2.4, MIN_GAP = 0.08;

  if (/Android/i.test(navigator.userAgent)) body.classList.add('is-android');

  const sections = [...document.querySelectorAll('[data-part]')];
  const navLinks = [...document.querySelectorAll('.nav a')];
  const heroMap = document.querySelector('.hero-map');
  let max = 1, tops = [], heroBottom = 0, intro = reduce ? HEAD : 0, ticking = false;

  const fmt = s => Math.floor(s / 60) + ':' + String(Math.round(s % 60)).padStart(2, '0');

  function measure() {
    // A window taller than the page would leave nothing to scroll: spread the map over most of the page anyway.
    max = Math.max(1, doc.scrollHeight - innerHeight, doc.scrollHeight * 0.8);
    tops = sections.map(s => s.getBoundingClientRect().top + scrollY);
    heroBottom = heroMap ? heroMap.getBoundingClientRect().bottom + scrollY : 400;
    let prev = -1;
    sections.forEach((s, i) => {
      let f = HEAD + (1 - HEAD) * clamp(tops[i] / max, 0, 1);
      f = clamp(Math.max(f, prev + MIN_GAP), 0, 0.97);
      prev = f;
      const id = s.dataset.part;
      const bar = 1 + Math.round(f * (BARS - 1)), t = (bar - 1) * SECS_PER_BAR;
      document.querySelectorAll('.fl[data-f="' + id + '"]').forEach(el => {
        el.style.setProperty('--f', (f * 100).toFixed(2) + '%');
        el.classList.toggle('flip', f > (el.closest('.mini') ? 0.7 : 0.88));
        const sm = el.querySelector('small');
        if (sm) sm.textContent = sm.dataset.bar + bar + ' · ' + fmt(t);
      });
      const pos = s.querySelector('.pos');
      if (pos) pos.textContent = pos.dataset.bar + bar + ' · ' + fmt(t);
    });
    frame();
  }

  function frame() {
    ticking = false;
    const p = clamp(scrollY / max, 0, 1);
    const x = (intro + (1 - HEAD) * p) * 100;
    doc.style.setProperty('--x', x.toFixed(2) + '%');
    document.querySelectorAll('.map').forEach(m => m.style.setProperty('--x', x.toFixed(2) + '%'));
    body.classList.toggle('scrolled', scrollY > heroBottom - 80);
    let cur = 0;
    const probe = scrollY + innerHeight * 0.4;
    tops.forEach((t, i) => { if (t <= probe) cur = i; });
    if (scrollY + innerHeight >= doc.scrollHeight - 4) cur = sections.length - 1;
    sections.forEach((s, i) => {
      const id = s.dataset.part;
      document.querySelectorAll('.fl[data-f="' + id + '"]').forEach(el => el.classList.toggle('on', i === cur));
    });
    navLinks.forEach(a => {
      if (a.getAttribute('href') === '#' + (sections[cur] && sections[cur].dataset.part)) a.setAttribute('aria-current', 'true');
      else a.removeAttribute('aria-current');
    });
  }
  const request = () => { if (!ticking) { ticking = true; requestAnimationFrame(frame); } };

  // The session starts: the playhead glides from the very beginning to the first cue of the first part.
  if (!reduce) {
    const t0 = performance.now();
    const step = now => {
      const k = clamp((now - t0 - 250) / 1100, 0, 1);
      intro = HEAD * (1 - Math.pow(1 - k, 3));
      frame();
      if (k < 1) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  }

  // Click on a map (not on a flag) to jump there, the way a click on a timeline moves the playhead.
  document.querySelectorAll('.map').forEach(m => m.addEventListener('click', e => {
    if (e.target.closest('.fl')) return;
    const r = m.getBoundingClientRect();
    const px = (e.clientX - r.left) / r.width;
    scrollTo({ top: clamp((px - HEAD) / (1 - HEAD), 0, 1) * max, behavior: reduce ? 'auto' : 'smooth' });
  }));

  addEventListener('scroll', request, { passive: true });
  addEventListener('resize', measure);
  addEventListener('load', measure);
  if ('ResizeObserver' in window) new ResizeObserver(measure).observe(body);
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(measure);
  measure();

  // ---- the cue: the app's flash and CueBanner, on the page ----
  const flashEl = document.querySelector('.flash');
  const bar = document.querySelector('.cuebar');
  let barTimer = 0, flashing = false;

  function flash() {
    if (!flashEl || flashing) return;
    flashing = true;
    const n = reduce ? 1 : 4;
    let i = 0;
    const pulse = () => {
      flashEl.classList.add('on');
      setTimeout(() => {
        flashEl.classList.remove('on');
        if (++i < n) setTimeout(pulse, 190); else flashing = false;
      }, reduce ? 450 : 190);
    };
    pulse();
  }

  function showBar(title, detail) {
    if (!bar) return;
    bar.querySelector('b').textContent = title;
    bar.querySelector('span').textContent = detail;
    bar.classList.add('on');
    clearTimeout(barTimer);
    barTimer = setTimeout(() => bar.classList.remove('on'), 3800);
  }

  const say = document.querySelector('[data-fire]');
  if (say) say.addEventListener('click', () => { showBar(say.dataset.title, say.dataset.detail); flash(); });

  // The cue demo: what the apps show for a phrase.
  const demo = document.querySelector('[data-demo]');
  if (demo) {
    const heard = demo.querySelector('[data-heard]'), banner = demo.querySelector('.banner');
    const bt = banner.querySelector('.t'), bd = banner.querySelector('.d');
    const devs = [...demo.querySelectorAll('.dev')];
    const chips = [...demo.querySelectorAll('.chip')];
    const show = (c, fire) => {
      chips.forEach(o => o.setAttribute('aria-pressed', String(o === c)));
      const text = c.dataset.text, hit = c.dataset.hit || '';
      heard.textContent = '';
      const at = hit ? text.toLowerCase().indexOf(hit.toLowerCase()) : -1;
      if (at >= 0) {
        heard.append(text.slice(0, at));
        const m = document.createElement('mark'); m.textContent = text.slice(at, at + hit.length); heard.append(m);
        heard.append(text.slice(at + hit.length));
      } else heard.textContent = text;
      if (c.dataset.cue) {
        banner.dataset.state = 'fired';
        bt.textContent = demo.dataset.cueLabel + ' · ' + c.dataset.cue.toUpperCase();
        bd.textContent = c.dataset.detail;
        if (fire) {
          flash();
          devs.forEach(d => { d.classList.add('pulse'); setTimeout(() => d.classList.remove('pulse'), 1500); });
        }
      } else {
        banner.dataset.state = 'idle';
        bt.textContent = demo.dataset.idleTitle;
        bd.textContent = c.dataset.detail;
      }
    };
    chips.forEach(c => c.addEventListener('click', () => show(c, true)));
    show(chips[0], false);
  }

  // Copy buttons.
  document.querySelectorAll('[data-copy]').forEach(b => b.addEventListener('click', async () => {
    const target = document.getElementById(b.dataset.copy);
    if (!target || !navigator.clipboard) return;
    try {
      await navigator.clipboard.writeText(target.textContent.trim());
      const old = b.textContent; b.textContent = b.dataset.done || 'Copied';
      setTimeout(() => { b.textContent = old; }, 1600);
    } catch (_) { /* clipboard blocked: the text stays selectable */ }
  }));
})();
