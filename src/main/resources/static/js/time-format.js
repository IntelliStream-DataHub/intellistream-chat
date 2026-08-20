/*
 * Copyright 2026 IntelliStream AS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * window.ChatTime — the one place a timestamp turns into text on the client.
 *
 * A message feed is rendered by two formatters: Thymeleaf draws the history you land on, and JS
 * draws everything that arrives after. Before this file they disagreed. The server formatted
 * Instants in the *server's* zone with a hard-coded 'h:mm a', so a UTC container showed US 12-hour
 * UTC timestamps directly above local 24-hour ones in the same list; the DM page had no correction
 * at all, and /search and /saved never had one either.
 *
 * The fix has two halves and this is the second one. The server now resolves the viewer's zone,
 * locale, clock and date order (TimeFormats -> TimeView) and publishes all four as meta tags
 * (fragments/time-prefs.html); this file rebuilds the same formatters with Intl and rewrites every
 * server-rendered timestamp from them. Both halves read the same four values, so they agree by
 * construction rather than by two implementations happening to match.
 *
 * It also closes the loop the server cannot close on its own. Accept-Language gets us as far as
 * "this person is probably in Norway"; only the browser knows the actual zone. On load we ask it,
 * adopt the answer (unless the user pinned a zone on their profile, which outranks everything),
 * re-render what is on screen, and post it back so the next page load is right server-side and
 * "/remind me at 14:00" means 14:00 where they are.
 *
 * Loads first in every bundle: chat-kit.js and friends delegate to it at definition time.
 */
window.ChatTime = (function () {
  'use strict';

  const meta = (name) => document.querySelector('meta[name="' + name + '"]')?.content || '';

  // What the server resolved. me-zone-source says which rung of its ladder answered — 'chosen',
  // 'detected', 'account', 'locale' or 'default' — and is the only thing that decides whether we
  // are allowed to overrule it below.
  const source = meta('me-zone-source') || 'default';
  const locale = meta('me-locale') || undefined;
  const hourCycle = meta('me-hour-cycle') || 'auto';
  const dateStyle = meta('me-date-style') || 'auto';
  const serverZone = meta('me-zone');

  /** The browser's own answer, or '' if it will not say. Never throws. */
  const detect = () => {
    try {
      return Intl.DateTimeFormat().resolvedOptions().timeZone || '';
    } catch (noIntl) {
      return '';
    }
  };

  const detected = detect();

  /*
   * Adoption. A profile choice is final — that is the entire reason it lives in its own column
   * server-side, and overruling it here would undo the one setting the user actually made. For
   * every other source the browser wins, because it is the only signal that knows where this
   * person is sitting: an IdP's zoneinfo claim was set once and forgotten, and an Accept-Language
   * guess is a guess. This mirrors TimeFormats.zoneFor exactly, so a page that adopts here and a
   * page rendered after the report below land on the same zone.
   */
  const adopted = source !== 'chosen' && detected && detected !== serverZone;
  const zone = adopted ? detected : (serverZone || detected || undefined);

  // ---------- Intl formatters ----------
  // Built once. A feed is hundreds of timestamps and Intl.DateTimeFormat construction is the
  // expensive half of formatting; reusing the instance is what keeps this off the render path.

  const withZone = (opts) => (zone ? Object.assign({ timeZone: zone }, opts) : opts);

  /**
   * Clock options that say the same thing as the server's HourCycle.
   *
   * 'auto' passes neither flag so CLDR decides, which is what DateTimeFormatter's localized SHORT
   * time does. 'h24' asks for hourCycle 'h23' rather than hour12:false — the latter leaves the
   * choice between a 0-23 and a 1-24 clock to the locale, and midnight then renders as 24:00 in a
   * handful of them.
   */
  const clockOpts = () => {
    if (hourCycle === 'h12') return { hour: 'numeric', minute: '2-digit', hour12: true };
    if (hourCycle === 'h24') return { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' };
    return { hour: 'numeric', minute: '2-digit' };
  };

  const build = (opts) => {
    try {
      return new Intl.DateTimeFormat(locale, withZone(opts));
    } catch (badZoneOrLocale) {
      // A zone tzdb knows and this browser does not, or a malformed language tag. Falling back to
      // the browser's own defaults keeps timestamps readable instead of blanking the feed.
      try {
        return new Intl.DateTimeFormat(undefined, opts);
      } catch (hopeless) {
        return null;
      }
    }
  };

  const timeFmt = build(clockOpts());
  const dayFmt = build({ weekday: 'long', month: 'long', day: 'numeric' });
  const dateAutoFmt = build({ dateStyle: 'medium' });
  const datePartsFmt = build({ day: 'numeric', month: 'short', year: 'numeric' });
  const numericFmt = build({ year: 'numeric', month: '2-digit', day: '2-digit' });
  const clockFmt = build({ hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });

  const asDate = (value) => (value instanceof Date ? value : new Date(value));
  const usable = (d) => d instanceof Date && !isNaN(d.getTime());

  /** Pull named fields out of a formatter's output so we can assemble a fixed order ourselves. */
  const partsOf = (fmt, d) => {
    const out = {};
    fmt.formatToParts(d).forEach((p) => { out[p.type] = p.value; });
    return out;
  };

  const safe = (fmt, value, render) => {
    const d = asDate(value);
    if (!fmt || !usable(d)) return '';
    try {
      return render ? render(d) : fmt.format(d);
    } catch (formatFailed) {
      return '';
    }
  };

  // ---------- Public formatting ----------

  /** A message's clock time: 14:05 or 2:05 PM. */
  const formatTime = (value) => safe(timeFmt, value);

  /** A day divider: "Tuesday, February 3" / "tirsdag 3. februar". */
  const formatDay = (value) => safe(dayFmt, value);

  /**
   * A date on its own, honouring the chosen order.
   *
   * 'auto' hands the whole job to Intl's medium style. The explicit orders cannot: passing
   * day/month/year to Intl asks for those *fields*, and the locale still decides the order they
   * come out in — which is exactly the thing the user overrode. So the parts are assembled here,
   * matching DateStyle.pattern() on the server.
   */
  const formatDate = (value) => {
    if (dateStyle === 'auto') return safe(dateAutoFmt, value);
    if (dateStyle === 'iso') return dayKey(value);
    return safe(datePartsFmt, value, (d) => {
      const p = partsOf(datePartsFmt, d);
      return dateStyle === 'mdy'
          ? p.month + ' ' + p.day + ', ' + p.year
          : p.day + ' ' + p.month + ' ' + p.year;
    });
  };

  /** Date and clock together, for tooltips and table cells. */
  const formatDateTime = (value) => {
    const date = formatDate(value);
    const time = formatTime(value);
    return date && time ? date + ', ' + time : (date || time);
  };

  /** Fixed yyyy-MM-dd HH:mm in the viewer's zone — the admin console's log-shaped columns. */
  const formatStamp = (value) => {
    const day = dayKey(value);
    const clock = safe(clockFmt, value);
    return day && clock ? day + ' ' + clock : '';
  };

  /**
   * The yyyy-MM-dd key day dividers group on, in the viewer's zone.
   *
   * Assembled from parts rather than formatted with a locale that happens to produce ISO, because
   * this is a key and not display text: it is compared between two messages to decide where a
   * divider goes, and it has to be the same shape the server put in data-day.
   */
  function dayKey(value) {
    return safe(numericFmt, value, (d) => {
      const p = partsOf(numericFmt, d);
      return p.year + '-' + p.month + '-' + p.day;
    });
  }

  // ---------- Rewriting what the server already rendered ----------

  /**
   * Re-render every element the templates marked with data-time-format.
   *
   * Unconditional, not only when the zone was adopted. The point is that one page shows one
   * convention: after this pass every timestamp on screen — server-rendered history, live arrivals,
   * search hits — came out of the formatters above, so there is no seam where two implementations
   * could drift. The instant is read from datetime (on a <time>) or data-time-value, and the
   * element is left exactly as the server rendered it if anything goes wrong.
   */
  const rewriteAll = (root) => {
    const scope = root || document;
    scope.querySelectorAll('[data-time-format]').forEach((el) => {
      const iso = el.getAttribute('datetime') || el.dataset.timeValue;
      if (!iso) return;
      let text = '';
      switch (el.dataset.timeFormat) {
        case 'time': text = formatTime(iso); break;
        case 'day': text = formatDay(iso); break;
        case 'date': text = formatDate(iso); break;
        case 'datetime': text = formatDateTime(iso); break;
        case 'stamp': text = formatStamp(iso); break;
        default: return;
      }
      if (text) el.textContent = text;
    });
  };

  // ---------- Reporting the detection back ----------

  const csrf = () => {
    const header = meta('_csrf_header');
    const token = meta('_csrf');
    return header && token ? { [header]: token } : {};
  };

  /**
   * Tell the server what the browser said, so the *next* page load is right without a round trip
   * and so reminders resolve "at 14:00" where this person actually is.
   *
   * Sent when the stored answer is not already this one: always for a locale guess or a bare
   * fallback (both of which store nothing, so they would keep guessing forever), and for a stored
   * detection only when it has changed — somebody who travelled. Never for a profile choice.
   *
   * Fire and forget. The page has already re-rendered itself from the adopted zone; this write only
   * matters later, and failing it should cost nothing and say nothing.
   */
  const report = () => {
    if (source === 'chosen' || !detected) return;
    if (source === 'detected' && detected === serverZone) return;
    fetch('/profile/timezone/detected', {
      method: 'POST',
      headers: Object.assign({ 'Content-Type': 'application/x-www-form-urlencoded' }, csrf()),
      body: 'zone=' + encodeURIComponent(detected),
    }).then(() => {
      // The banner asks a question the browser has now answered. Take it down without waiting for
      // a reload; the server has recorded the same thing and will not render it again.
      document.getElementById('zone-prompt')?.remove();
    }).catch(() => { /* a hint, not a transaction */ });
  };

  /*
   * The banner's dismiss button. Wired here rather than in either page's chrome module because the
   * banner is rendered on both the channel and the DM page and those have separate chrome modules,
   * while this file is in every bundle. The row goes immediately; the POST only records that it
   * should not come back.
   */
  const wireDismiss = () => {
    const banner = document.getElementById('zone-prompt');
    if (!banner) return;
    banner.querySelector('#zone-prompt-dismiss')?.addEventListener('click', () => {
      banner.remove();
      fetch('/profile/timezone/prompt/dismiss', { method: 'POST', headers: csrf() })
          .catch(() => { /* it is gone from this page either way */ });
    });
  };

  // Scripts are deferred, so the document is parsed by the time this runs and the rewrite can
  // happen immediately — before first paint in practice, so nothing is seen to change.
  rewriteAll();
  wireDismiss();
  report();

  return {
    zone: zone,
    locale: locale,
    source: source,
    /** True when the browser's zone overruled what the server had. */
    adopted: adopted,
    detected: detected,
    twelveHour: meta('me-twelve-hour') === 'true',
    formatTime: formatTime,
    formatDay: formatDay,
    formatDate: formatDate,
    formatDateTime: formatDateTime,
    formatStamp: formatStamp,
    dayKey: dayKey,
    rewriteAll: rewriteAll,
  };
})();
