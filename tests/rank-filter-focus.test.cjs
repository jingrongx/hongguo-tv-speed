const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
require('./helpers/app-text.cjs');
const source = fs.readFileSync(require.resolve('../core/cmd/hongguotv/ui/app.js'), 'utf8');

function slice(a, b) {
  return source.slice(source.indexOf('  function ' + a + '('), source.indexOf('  function ' + b + '('));
}

// Minimal DOM stubs: elements know their class set, parent chain, client
// rects (empty when an ancestor collapses them) and bounding rect.
function element(id, classes, parent, rendered) {
  const classSet = new Set(classes);
  const node = {
    id,
    disabled: false,
    classList: {
      contains: name => classSet.has(name),
      add: name => classSet.add(name),
      remove: name => classSet.delete(name),
      toggle() {}
    },
    getClientRects: () => (rendered ? [{}] : []),
    getBoundingClientRect: () => ({ left: 0, top: 0, width: 10, height: 10 })
  };
  node.parent = parent;
  node.closest = selector => {
    if (selector !== '.is-hidden') return null;
    for (let at = node; at; at = at.parent) {
      if (at.classList && at.classList.contains('is-hidden')) return at;
    }
    return null;
  };
  return node;
}

function routerContext(world) {
  const nodes = world.nodes;
  const hiddenRoot = world.hiddenRoot;
  const visible = node => {
    for (let at = node; at; at = at.parent) if (at === hiddenRoot) return false;
    return true;
  };
  const matches = (node, selector) => selector.split(',').some(part => {
    const tokens = part.trim().split('.').filter(Boolean);
    const rest = tokens[0] && tokens[0].startsWith('#') ? tokens.slice(1) : tokens;
    if (tokens[0] && tokens[0].startsWith('#') && node.id !== tokens[0].slice(1)) return false;
    return rest.every(name => node.classList.contains(name));
  });
  const context = vm.createContext({
    zoneSelectors: { rankPanels: '.rank-panel', rankModal: '.rank-filter-option', rankItems: '.rank-row', rankSubnav: '.rank-subtab', rankTabs: '.rank-tab' },
    document: { getElementById: () => null, querySelector: () => null },
    numericKeypad: null,
    ...world
  });
  context.focusables = selector => nodes.filter(node => matches(node, selector) && visible(node) && !node.closest('.is-hidden'));
  context.firstVisible = selector => context.focusables(selector)[0] || null;
  context.zoneOf = node => {
    if (node.classList.contains('rank-filter-toggle')) return 'rankFilters';
    if (node.classList.contains('rank-panel')) return 'rankPanels';
    if (node.classList.contains('rank-row')) return 'rankItems';
    if (node.classList.contains('rank-subtab')) return 'rankSubnav';
    if (node.classList.contains('rank-tab')) return 'rankTabs';
    return '';
  };
  Object.assign(context, {
    firstRankTab() { return null; },
    moveModalFrom() { return null; },
    moveCoreConnectionFocus() { return null; },
    moveDiagnosticFocus() { return null; },
    moveDiscussion() { return null; },
    activeSidebar() { return null; },
    firstContentFocus() { return null; },
    listBoundary() { return null; },
    rankPager: null,
    scanModalOpen: () => false,
    supportModalOpen: false,
    coreConnectionModalOpen: false,
    detailModalOpen: false,
    discussionOpen: false,
    confirmOpen: false,
    playbackCommandMenuOpen: false,
    actorWorksOpen: false,
    playerOptionsOpen: false,
    accountCountryModalOpen: false,
    rankFilterOpen: false,
    detailFocusPending: false
  });
  vm.runInContext(slice('firstRankFilterToggle', 'firstNewTab') + slice('moveWithinZone', 'accountKeypadOpen'), context);
  return context;
}

function rankWorld({ panelsRendered }) {
  // Collapsed filters hide #rank-panels entirely (the chips keep empty
  // client rects); expanded filters render the chips into the page.
  const root = { isRoot: true };
  const hiddenRoot = { isHiddenRoot: true };
  const chipHome = panelsRendered ? root : hiddenRoot;
  const toggle = element('rank-filter-toggle', ['rank-filter-toggle'], root, true);
  const subtab = element('rank-subtab-hot', ['rank-subtab', 'is-active'], root, true);
  const panelActive = element('rank-panel-dim', ['rank-panel', 'is-active'], chipHome, panelsRendered);
  const panelOther = element('rank-panel-genre', ['rank-panel'], chipHome, panelsRendered);
  const row = element('rank-item-1', ['rank-row'], root, true);
  return {
    hiddenRoot,
    nodes: [toggle, subtab, panelActive, panelOther, row],
    toggle, subtab, panelActive, panelOther, row
  };
}

test('down from the collapsed rank filter toggle reaches the first drama row', () => {
  const world = rankWorld({ panelsRendered: false });
  const c = routerContext(world);
  assert.equal(c.moveFrom(world.toggle, 'down'), world.row);
  assert.equal(c.moveFrom(world.toggle, 'right'), world.row);
});

test('down from the expanded rank filter toggle lands on the selected filter chip', () => {
  const world = rankWorld({ panelsRendered: true });
  const c = routerContext(world);
  assert.equal(c.moveFrom(world.toggle, 'down'), world.panelActive);
});

test('up from the first drama row returns to the filter toggle when chips are collapsed', () => {
  const world = rankWorld({ panelsRendered: false });
  const c = routerContext(world);
  assert.equal(c.moveFrom(world.row, 'up'), world.toggle);
});

test('right from the rank subnav skips collapsed chips and offers the toggle first', () => {
  const world = rankWorld({ panelsRendered: false });
  const c = routerContext(world);
  assert.equal(c.moveFrom(world.subtab, 'right'), world.toggle);
});
