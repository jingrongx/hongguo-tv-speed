const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
require('./helpers/app-text.cjs');
const source = fs.readFileSync(require.resolve('../core/cmd/hongguotv/ui/app.js'), 'utf8');

function slice(a, b) {
  return source.slice(source.indexOf('  function ' + a + '('), source.indexOf('  function ' + b + '('));
}

function revealFixture({ focusZone, modalOpen, lastControl }) {
  const calls = { focused: [], scheduled: 0 };
  const feedElement = { id: 'hero-feed-focus', tagName: 'DIV' };
  const dialButton = { id: 'dial-play', tagName: 'BUTTON' };
  const lastControlElement = { id: 'dial-episodes', tagName: 'BUTTON' };
  const current = focusZone === 'controls' ? dialButton : feedElement;
  const context = vm.createContext({
    controlsManuallyHidden: true,
    shell: { classList: { remove() {}, contains: () => false } },
    isPlaybackPage: () => true,
    pageMode: () => 'recommend',
    detailModalOpen: !!modalOpen,
    actorWorksOpen: false,
    confirmOpen: false,
    playerOptionsOpen: false,
    discussionOpen: false,
    coreConnectionModalOpen: false,
    playbackCommandMenuOpen: false,
    endRecommendations: { isOpen: () => false },
    lastPlaybackControl: lastControl ? lastControlElement : null,
    remoteFocusTarget: () => current,
    zoneOf: element => (element === dialButton ? 'controls' : focusZone),
    document: { contains: () => true },
    firstVisible: selector => (selector === '.playback-buttons .dial-button' ? dialButton : null),
    goFocus: element => calls.focused.push(element.id),
    scheduleControlsHide: () => { calls.scheduled += 1; }
  });
  vm.runInContext(slice('revealControls', 'hideControls'), context);
  return { context, calls, dialButton, lastControlElement };
}

test('revealing the control bar parks remote focus on the playback dial', () => {
  const f = revealFixture({ focusZone: 'feed' });
  f.context.revealControls();
  assert.deepEqual(f.calls.focused, ['dial-play']);
  assert.equal(f.calls.scheduled, 1);
  assert.equal(f.context.controlsManuallyHidden, false);
});

test('the last used control button wins as the reveal anchor', () => {
  const f = revealFixture({ focusZone: 'feed', lastControl: true });
  f.context.revealControls();
  assert.deepEqual(f.calls.focused, ['dial-episodes']);
});

test('focus already inside the bar is left alone by a reveal', () => {
  const f = revealFixture({ focusZone: 'controls' });
  f.context.revealControls();
  assert.deepEqual(f.calls.focused, []);
});

test('open overlays keep their focus when the bar is revealed underneath', () => {
  const f = revealFixture({ focusZone: 'feed', modalOpen: true });
  f.context.revealControls();
  assert.deepEqual(f.calls.focused, []);
});
