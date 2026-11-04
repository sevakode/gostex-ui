package tv.orbit.shield

/** Page-local presentation only: no native bridge, network calls, or Grafana mutations. */
internal object GrafanaAutoScroll {
    val stopScript = "window.__gostexGrafanaScroll?.stop();"

    fun script(mode: GrafanaScrollMode): String {
        val speed = when (mode) {
            GrafanaScrollMode.OFF -> return stopScript
            GrafanaScrollMode.SLOW -> 6
            GrafanaScrollMode.MEDIUM -> 12
            GrafanaScrollMode.FAST -> 22
        }
        return template.replace("__PIXELS_PER_SECOND__", speed.toString())
    }

    // Slow scrolling keeps a fractional position: rounding every individual frame would stall
    // below one pixel/frame. Discovery samples a few viewport points and their ancestors rather
    // than walking every panel/table cell in a large dashboard. While stationary we wake rarely.
    private val template = """
        (() => {
          const key = '__gostexGrafanaScroll';
          const speed = __PIXELS_PER_SECOND__;
          const routeKey = () => location.origin + location.pathname;
          const previous = window[key];
          if (previous && !previous.stopped) {
            if (previous.route === routeKey()) { previous.setSpeed(speed); return; }
            previous.stop();
          }
          if (document.hidden) return;
          const initialRoute = routeKey();
          const idleMs = 2000, frameMs = 33, dwellMs = 6000;
          let timer = 0, target = null, savedStyle = null;
          let position = 0, maximum = 0, direction = 1, pixelsPerSecond = speed;
          let last = performance.now(), checkAfter = 0, dwellUntil = 0;
          const state = {
            stopped: false,
            route: initialRoute,
            setSpeed(value) { pixelsPerSecond = value; },
            stop() {
              if (state.stopped) return;
              state.stopped = true;
              clearTimeout(timer);
              restoreStyle();
              window.removeEventListener('pagehide', state.stop);
              window.removeEventListener('popstate', onRouteChange);
              window.removeEventListener('hashchange', onRouteChange);
              document.removeEventListener('visibilitychange', onVisibility);
              if (window[key] === state) delete window[key];
            }
          };
          window[key] = state;

          function restoreStyle() {
            if (target && savedStyle) {
              for (const [name, value, priority] of savedStyle) {
                if (value) target.style.setProperty(name, value, priority);
                else target.style.removeProperty(name);
              }
            }
            target = null;
            savedStyle = null;
          }

          function onVisibility() { if (document.hidden) state.stop(); }
          function onRouteChange() { if (routeKey() !== initialRoute) state.stop(); }

          function obstructed() {
            // An expired session may render a login form after the document has loaded.
            // A modal/editor also belongs to the person, never to the screensaver animator.
            if (/(^|\/)(login|signin|sign-in|auth|oauth|oauth2|sso)(\/|$)/i.test(location.pathname)) return true;
            return Array.from(document.querySelectorAll(
              'input[type="password"], [role="dialog"], [aria-modal="true"]'
            )).some(element => element.getClientRects().length > 0);
          }

          function candidateScore(element) {
            if (!element || !element.isConnected || element.clientHeight <= 0) return 0;
            if (element.scrollHeight - element.clientHeight <= 2) return 0;
            const root = element === document.scrollingElement;
            const style = getComputedStyle(element);
            if (style.visibility !== 'visible' || style.display === 'none') return 0;
            if (!root && !/^(auto|scroll|overlay)$/.test(style.overflowY)) return 0;
            const rect = root ? { left: 0, top: 0, right: innerWidth, bottom: innerHeight }
              : element.getBoundingClientRect();
            const width = Math.max(0, Math.min(innerWidth, rect.right) - Math.max(0, rect.left));
            const height = Math.max(0, Math.min(innerHeight, rect.bottom) - Math.max(0, rect.top));
            // Exclude navigation sidebars and small tables embedded within a dashboard.
            if (width < innerWidth * .45 || height < innerHeight * .35) return 0;
            return width * height;
          }

          function findTarget() {
            const candidates = new Set([document.scrollingElement]);
            for (const x of [.25, .5, .75]) for (const y of [.25, .5, .75]) {
              for (let element of document.elementsFromPoint(innerWidth * x, innerHeight * y)) {
                let depth = 0;
                while (element && depth++ < 32) {
                  candidates.add(element);
                  element = element.parentElement;
                }
              }
            }
            let winner = null, score = 0;
            for (const element of candidates) {
              const next = candidateScore(element);
              if (next > score) { winner = element; score = next; }
            }
            return winner;
          }

          function useTarget(element, now) {
            restoreStyle();
            target = element;
            savedStyle = ['scroll-behavior', 'scroll-snap-type'].map(name =>
              [name, target.style.getPropertyValue(name), target.style.getPropertyPriority(name)]);
            target.style.setProperty('scroll-behavior', 'auto', 'important');
            target.style.setProperty('scroll-snap-type', 'none', 'important');
            position = target.scrollTop;
            maximum = Math.max(0, target.scrollHeight - target.clientHeight);
            direction = position >= maximum - 1 ? -1 : 1;
            dwellUntil = now + dwellMs;
          }

          function schedule(delay) {
            if (!state.stopped) timer = setTimeout(tick, delay);
          }

          function tick() {
            if (state.stopped) return;
            if (document.hidden || routeKey() !== initialRoute) { state.stop(); return; }
            const now = performance.now();
            const elapsed = Math.min(100, Math.max(0, now - last));
            last = now;
            if (now >= checkAfter) {
              checkAfter = now + idleMs;
              if (obstructed()) {
                restoreStyle();
                schedule(idleMs);
                return;
              }
              if (!target || candidateScore(target) === 0) {
                const next = findTarget();
                if (next) useTarget(next, now); else restoreStyle();
              }
              if (target) maximum = Math.max(0, target.scrollHeight - target.clientHeight);
            }
            if (!target) { schedule(idleMs); return; }
            if (now < dwellUntil) { schedule(Math.min(1000, dwellUntil - now)); return; }
            // Respect a changed scroll position (layout, restored state, or Grafana itself).
            if (Math.abs(target.scrollTop - position) > 2) position = target.scrollTop;
            position = Math.min(maximum, Math.max(0, position + direction * pixelsPerSecond * elapsed / 1000));
            target.scrollTop = position;
            if ((direction > 0 && position >= maximum) || (direction < 0 && position <= 0)) {
              direction *= -1;
              dwellUntil = now + dwellMs;
            }
            schedule(frameMs);
          }

          window.addEventListener('pagehide', state.stop);
          window.addEventListener('popstate', onRouteChange);
          window.addEventListener('hashchange', onRouteChange);
          document.addEventListener('visibilitychange', onVisibility);
          schedule(0);
        })();
    """.trimIndent()
}
