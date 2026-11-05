// Regression: the auto-retry wait countdown must update the status line in
// place. It used to call setPlayerStatus(…, true), whose failed branch
// schedules the retry again — an infinite mutual recursion that stack-
// overflowed the page on every visible playback failure and blocked the JS
// thread (and with it every remote key) for minutes on TV boxes.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
require('./helpers/app-text.cjs');
const source = fs.readFileSync(require.resolve('../core/cmd/hongguotv/ui/app.js'), 'utf8');

const setStatus = source.slice(source.indexOf('  function setPlayerStatus('), source.indexOf('  function episodeLoadingText('));
const retryBlock = source.slice(source.indexOf('  // A shown failure panel never just sits there'), source.indexOf('  function finishPlaybackPreparation('));

function fixture(options) {
  const classes = (...initial) => { const set = new Set(initial); return { contains: n => set.has(n), add: (...v) => v.forEach(n => set.add(n)), remove: (...v) => v.forEach(n => set.delete(n)), toggle(n, on) { if (on) set.add(n); else set.delete(n); } } };
  const node = (...initial) => ({ textContent: '', classList: classes(...initial) });
  const hero = node('is-feed', 'is-preparing-video'), title = node('is-hidden'), text = node(), spinner = node(), retry = node('is-hidden'), diagnostics = node('is-hidden'), panel = node('is-hidden');
  panel.querySelector = selector => { assert.equal(selector, '.player-spinner'); return spinner; };
  const nodes = { 'hero': hero, 'player-status': panel, 'player-status-title': title, 'player-status-text': text, 'player-retry': retry, 'player-failure-diagnostics': diagnostics };
  const frames = [];
  const timers = [];
  const context = {
    document: { getElementById: id => nodes[id] },
    activePlayback: null, autoCinemaCandidate: false, featuredItem: { title: '新剧名称' },
    reveals: 0, focused: null, coverClears: 0, zoneOf: () => 'feed',
    playbackRequest: { index: 0, episode: 3, position: 0 },
    applicationInBackground: false, applicationHidden: () => false, isPlaybackPage: () => true,
    playFeedItemCalls: [],
    setTimeout(callback, delay) { timers.push({ callback, delay }); return timers.length; },
    clearTimeout() {},
  };
  if (options && options.frameScheduler) context.requestAnimationFrame = callback => { frames.push(callback); };
  const c = vm.createContext(context);
  vm.runInContext(
    'function clearLastPlaybackFrame(){}function clearEpisodeCover(){coverClears++}function cancelPendingAutoCinema(){autoCinemaCandidate=false}' +
    'function revealControls(){reveals++}function goFocus(node){focused=node}' +
    'function showNotice(){}function playFeedItem(index, episode, position, continuation, checkpoint, recovery){playFeedItemCalls.push({index, episode, position, recovery})}' +
    'function notePlaybackFailure(){}' + setStatus + retryBlock, c);
  return { c, hero, title, text, spinner, retry, diagnostics, panel, timers, paint() { const pending = frames.splice(0); pending.forEach(callback => callback()); } };
}

test('failure status schedules the auto-retry without re-entering the failure path', () => {
  const f = fixture();
  // With the recursion this call never returns (RangeError: maximum call stack).
  f.c.setPlayerStatus('暂时无法播放这集', true);
  assert.equal(f.c.reveals, 1);
  assert.equal(f.retry.classList.contains('is-hidden'), false);
  // The countdown lands in the same status line, in place.
  assert.match(f.text.textContent, /约 10 秒后自动重试/);
  // Exactly one retry is armed; the timer re-runs the pending episode.
  assert.equal(f.timers.length, 1);
  f.timers[0].callback();
  assert.equal(f.c.playFeedItemCalls.length, 1);
  assert.equal(f.c.playFeedItemCalls[0].index, 0);
  assert.equal(f.c.playFeedItemCalls[0].episode, 3);
  // The automatic retry carries a recovery marker: it must not re-show the
  // opaque episode-transition card over the failure panel.
  assert.ok(f.c.playFeedItemCalls[0].recovery && f.c.playFeedItemCalls[0].recovery.recoveryAttempts >= 1);
});

test('a throttled failure aligns the countdown with the reported cooldown window', () => {
  const f = fixture();
  // Assigned after the slice ran: the sliced `var` would shadow a fixture value.
  f.c.lastPlaybackFailureNote = '上游限流，约 77 秒后再试';
  f.c.setPlayerStatus('暂时无法播放这集', true);
  assert.match(f.text.textContent, /约 80 秒后自动重试/);
  assert.equal(f.timers.length, 1);
  assert.ok(f.timers[0].delay >= 77000);
});

test('clearing the failure cancels the armed retry', () => {
  const f = fixture();
  f.c.setPlayerStatus('暂时无法播放这集', true);
  assert.equal(f.timers.length, 1);
  f.c.setPlayerStatus('');
  // cancelFailureAutoRetry clears the handle; the timer cannot fire afterwards.
  assert.equal(f.c.failureAutoRetryTimer, 0);
});
