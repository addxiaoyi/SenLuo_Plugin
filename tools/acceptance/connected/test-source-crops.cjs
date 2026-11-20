'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { createHash } = require('node:crypto')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const vm = require('node:vm')
const scenario = require('./source-crops.cjs')
const c = scenario.contract
const sha = value => createHash('sha256').update(value).digest('hex')
function item (id, count = 1, extra = {}) {
  const unit = Buffer.from(JSON.stringify({ id, pdc: {}, ...extra, count: 1 })).toString('base64')
  return { id, count, material: 'PAPER', pdc: {}, serialized: Buffer.from('full:' + id + ':' + count).toString('base64'),
    unitSerialized: unit, unitSha256: sha(Buffer.from(unit, 'base64')), undamagedUnitSerialized: unit,
    damage: 0, enchantments: {}, ...extra }
}
function snapshot () {
  const values = Object.fromEntries(c.PLAYERS.map((name, index) => [name, { uuid: 'uuid-' + index, world: 'world-uuid',
    inventory: Array.from({ length: 36 }, () => ({})), armor: Array.from({ length: 4 }, () => ({})), offHand: {}, cursor: {},
    heldSlot: 0, gameMode: 'SURVIVAL', op: false, owned: true, online: true, position: [728 + index, 200, 13] }]))
  const canonicalItems = Object.fromEntries(['tomato_seed', 'chili_seed', 'lettuce_seed', 'tomato', 'red_chili', 'green_chili', 'lettuce', 'caterpillar']
    .map(name => [c.NS + name, item(c.NS + name)]))
  return { players: values, canonicalItems, world: { uuid: 'world-uuid', name: 'acceptance-world', items: [] },
    events: [], setupWrites: [], fixture: {} }
}
function crop (name = 'tomato', age = 0) {
  return { loaded: true, owned: true, id: c.NS + name + '_crop', age,
    maintainsFarmland: true, nativeQueryErrors: [], shape: [[0, 0, 0, 1, (age + 2) / 16, 1]], collision: [], supportShape: [] }
}

test('source crop loot bounds retain empty-tool sickle semantics and ordinary Fortune growth', () => {
  for (const name of c.CROPS) {
    assert.deepEqual(c.lootRanges(name, 0), { [c.NS + name + '_seed']: [1, 1] })
    assert.equal(c.lootRanges(name, 7)[c.NS + name + '_seed'][1], 1)
    assert.equal(c.lootRanges(name, 7, 3)[c.NS + name + '_seed'][1], 10)
  }
  assert.deepEqual(c.lootRanges('chili', 7)[c.NS + 'green_chili'], [0, 1])
  assert.deepEqual(c.lootRanges('lettuce', 7)[c.NS + 'caterpillar'], [0, 1])
})
test('mature seed observations 8..10 require Fortune III and no Fortune level permits 11', () => {
  for (const count of [8, 9, 10]) {
    assert.equal(c.assertFortune('tomato', 3, count), count)
    for (const fortune of [0, 1, 2]) assert.throws(() => c.assertFortune('tomato', fortune, count))
  }
  assert.throws(() => c.assertFortune('tomato', 3, 11))
  for (const fortune of [0, 1, 2, 3]) assert.throws(() => c.assertFortune('tomato', fortune, 11))
})
test('all 24 final native age shapes have the source dimensions and no collision', () => {
  for (const name of c.CROPS) for (let age = 0; age <= 7; age++) c.assertCrop(crop(name, age), name, age)
})
test('the native farmland tag is required even when optional shape comparisons are disabled', () => {
  const value = crop('tomato', 7); value.maintainsFarmland = false
  assert.throws(() => c.assertCrop(value, 'tomato', 7, false), /maintain farmland/)
})
for (const [name, mutate] of Object.entries({
  'visual placeholder identity': b => { b.id = 'minecraft:tripwire' },
  'stale native cache': b => { b.shape = [[0, 0, 0, 1, 1, 1]] },
  'native query failure': b => { b.nativeQueryErrors = ['missing method'] },
  'missing native farmland tag': b => { delete b.maintainsFarmland },
  'false native farmland tag': b => { b.maintainsFarmland = false },
  'collision volume': b => { b.collision = [[0, 0, 0, 1, 1, 1]] },
  'cross-region read': b => { b.owned = false },
  'unloaded target': b => { b.loaded = false },
  'noninteger age': b => { b.age = '7' }
})) test('native source state rejects ' + name, () => {
  const value = crop('tomato', 7); mutate(value)
  assert.throws(() => c.assertCrop(value, 'tomato', 7))
})
test('complete component totals conserve stack splits, player transfers, and ground pickup', () => {
  const before = snapshot(), after = snapshot(), id = c.NS + 'tomato_seed'
  before.players[c.PLAYERS[0]].inventory[0] = item(id, 4)
  after.players[c.PLAYERS[0]].inventory[3] = item(id, 1)
  after.players[c.PLAYERS[1]].offHand = item(id, 2)
  after.world.items.push({ ...item(id), uuid: 'drop-1', world: 'world-uuid', position: [724, 200, 4] })
  assert.deepEqual(c.holdings(before), c.holdings(after)); c.expectDelta(before, after)
})
test('canonical rewards cannot accept an arbitrary item sharing a CE id', () => {
  const before = snapshot(), after = snapshot(), id = c.NS + 'tomato'
  after.players[c.PLAYERS[0]].inventory[0] = item(id, 1, { pdc: { forged: 'same paper different data' } })
  assert.throws(() => c.expectDelta(before, after, { [id]: [1, 1] }), /canonical/)
})
test('one fruit recovery rejects duplicates, missing fruits, unrelated losses and a second seed', () => {
  const id = c.NS + 'tomato'
  for (const count of [0, 2]) {
    const before = snapshot(), after = snapshot()
    if (count) after.players[c.PLAYERS[0]].inventory[0] = item(id, count)
    assert.throws(() => c.expectDelta(before, after, { [id]: [1, 1] }))
  }
  const before = snapshot(), after = snapshot()
  after.players[c.PLAYERS[0]].inventory[0] = item(id)
  after.players[c.PLAYERS[1]].inventory[0] = item(c.NS + 'tomato_seed')
  assert.throws(() => c.expectDelta(before, after, { [id]: [1, 1] }), /Unrelated/)
})
test('normal loot permits the specified independent extras without multiplying its fruit', () => {
  const before = snapshot(), after = snapshot(), inv = after.players[c.PLAYERS[0]].inventory
  inv[0] = item(c.NS + 'chili_seed', 1); inv[1] = item(c.NS + 'red_chili'); inv[2] = item(c.NS + 'green_chili')
  c.expectDelta(before, after, c.lootRanges('chili', 7))
  inv[1].count = 2
  assert.throws(() => c.expectDelta(before, after, c.lootRanges('chili', 7)))
})
test('tool normalization permits only the declared durability change with exact other components', () => {
  const before = snapshot(), after = snapshot(), tool = item(c.NS + 'sickle')
  before.players[c.PLAYERS[0]].inventory[4] = tool
  after.players[c.PLAYERS[0]].inventory[4] = { ...tool, damage: 1,
    unitSerialized: Buffer.from('same tool damage1').toString('base64'), unitSha256: sha('same tool damage1') }
  c.expectDelta(before, after, {}, { actor: c.PLAYERS[0], slot: 4, damage: 1 })
  after.players[c.PLAYERS[0]].inventory[4].pdc = { injected: 1 }
  assert.throws(() => c.expectDelta(before, after, {}, { actor: c.PLAYERS[0], slot: 4, damage: 1 }), /beyond durability/)
})
for (const [name, mutate] of Object.entries({
  'missing full serialization': v => { delete v.serialized },
  'missing unit serialization': v => { delete v.unitSerialized },
  'false checksum': v => { v.unitSha256 = '0'.repeat(64) },
  'noncanonical base64': v => { v.unitSerialized += '\n' },
  'boolean count': v => { v.count = true },
  'negative count': v => { v.count = -1 },
  'missing PDC': v => { delete v.pdc },
  'missing durability': v => { delete v.damage }
})) test('component evidence rejects ' + name, () => {
  const value = item(c.NS + 'tomato'); mutate(value); assert.throws(() => c.itemKey(value))
})
for (const [name, mutate] of Object.entries({
  'missing storage slot': v => { v.players[c.PLAYERS[0]].inventory.pop() },
  'missing armor slot': v => { v.players[c.PLAYERS[1]].armor.pop() },
  'OP player': v => { v.players[c.PLAYERS[0]].op = true },
  'unowned player': v => { v.players[c.PLAYERS[1]].owned = false },
  'unobserved peer': v => { delete v.players[c.PLAYERS[1]] },
  'cursor-held item': v => { v.players[c.PLAYERS[0]].cursor = item(c.NS + 'tomato') },
  'wrong ground world': v => { v.world.items = [{ ...item(c.NS + 'tomato'), uuid: '1', world: 'other' }] },
  'duplicate ground UUID': v => { v.world.items = [1, 2].map(() => ({ ...item(c.NS + 'tomato'), uuid: '1', world: 'world-uuid' })) }
})) test('conservation rejects ' + name, () => { const value = snapshot(); mutate(value); assert.throws(() => c.holdings(value)) })
test('event history allows shared sequence gaps and exactly one deferred native finalization', () => {
  const old = { events: [{ seq: 3, type: 'BlockGrowEvent', cancelled: false, after: null, finalObservedAt: null,
    finalProposed: null, finalDecisions: null }], setupWrites: [{ seq: 1, before: 'old', after: 'new' }] }
  const now = structuredClone(old)
  Object.assign(now.events[0], { after: { age: 1 }, finalObservedAt: 100, finalProposed: { age: 1 }, finalDecisions: { cancelled: false } })
  now.events.push({ seq: 8, type: 'PlayerInteractEvent' })
  c.auditHistory(old, now)
  const changed = structuredClone(now); changed.events[0].after.age = 2
  assert.throws(() => c.auditHistory(now, changed), /Final native/)
})
for (const [name, mutate] of Object.entries({
  'truncated events': v => { v.events = [] },
  'rewritten event': v => { v.events[0].type = 'OtherEvent' },
  'rewritten setup': v => { v.setupWrites[0].after = 'forged' },
  'duplicate sequence': v => { v.events.push({ ...v.events[0] }) },
  'boolean sequence': v => { v.events[0].seq = true }
})) test('cumulative receipt audit rejects ' + name, () => {
  const before = { events: [{ seq: 2, type: 'BlockGrowEvent', finalObservedAt: 1 }], setupWrites: [{ seq: 1, after: 'real' }] }
  const after = structuredClone(before); mutate(after); assert.throws(() => c.auditHistory(before, after))
})
test('restart checkpoint validates the six fixed plants, both players, phase and run attribution', () => {
  const value = { schemaVersion: 1, scenario: 'sourcecrops', phase: 'seed', runId: 'case-run',
    players: Object.fromEntries(c.PLAYERS.map(name => [name, {}])), fixtures: Object.fromEntries(c.PERSISTED.map(name => [name, {}])) }
  c.validateCheckpoint(value, 'seed', 'case-run')
  for (const [key, content] of [['scenario', 'gameplay'], ['phase', 'verify'], ['runId', 'other'], ['schemaVersion', true]]) {
    assert.throws(() => c.validateCheckpoint({ ...value, [key]: content }, 'seed', 'case-run'))
  }
})
test('verify refuses a foreign seed before any probe observation or mutation', async t => {
  const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'source-crop-contract-'))
  t.after(() => fs.rmSync(folder, { recursive: true, force: true }))
  const file = path.join(folder, 'state.json'); fs.writeFileSync(file, JSON.stringify({ schemaVersion: 1, scenario: 'gameplay' }))
  let reads = 0
  await assert.rejects(scenario({ phase: 'verify', stateFile: file, out: path.join(folder, 'verify'), report: { checks: {} }, inspect: () => { reads++ } }))
  assert.equal(reads, 0)
})
test('empty refuses a verify sidecar bound to another seed before any probe command', async t => {
  const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'source-crop-contract-'))
  t.after(() => fs.rmSync(folder, { recursive: true, force: true }))
  const file = path.join(folder, 'state.json'), runId = path.basename(folder)
  const base = { schemaVersion: 1, scenario: 'sourcecrops', phase: 'seed', runId,
    players: Object.fromEntries(c.PLAYERS.map(name => [name, {}])), fixtures: Object.fromEntries(c.PERSISTED.map(name => [name, {}])) }
  fs.writeFileSync(file, JSON.stringify(base))
  fs.writeFileSync(file + '.verified.json', JSON.stringify({ ...base, phase: 'verify', seedStateSha256: '0'.repeat(64) }))
  let reads = 0
  await assert.rejects(scenario({ phase: 'empty', stateFile: file, out: path.join(folder, 'empty'), report: { checks: {} }, inspect: () => { reads++ } }), /another seed/)
  assert.equal(reads, 0)
})
test('sourcecrops connection performs its separate arena/arrival route without the legacy terrain writer', async () => {
  const source = fs.readFileSync(path.join(__dirname, 'players.cjs'), 'utf8')
  const text = source.slice(source.indexOf('async function connect ('), source.indexOf("const ns = 'kaleidoscopecookery:'"))
  const calls = []
  const bot = { entity: { position: { y: 200 } }, blockAt: () => ({ name: 'stone' }), on: () => {}, once: () => {}, _client: { on: () => {} } }
  const context = { scenario: 'sourcecrops', phase: 'seed', mineflayer: { createBot: () => bot }, port: 1,
    sessions: [], pendingPacks: new Set(), shutdown: { signal: { aborted: false } }, networkError: null,
    report: { packs: { SenLuoProbeA: {} } }, until: async (_description, fn) => { assert.ok(await fn()) }, Vec3: class {},
    inspectCrop: async (_bot, action) => { calls.push(action); return { arrived: true } },
    inspect: async () => { throw new Error('Legacy arrive must never run') }, assert, receivePack: () => {} }
  vm.createContext(context); vm.runInContext(text + '; this.runConnect = connect', context)
  assert.equal(await context.runConnect('SenLuoProbeA'), bot)
  assert.deepEqual(calls, ['stagearena', 'arrive'])
})

function canceledBone (kind = 'grow', key = 'tomato') {
  const before = snapshot(), position = c.POSITIONS[key], type = { grow: 'BlockGrowEvent', fertilize: 'BlockFertilizeEvent', interact: 'PlayerInteractEvent' }[kind]
  before.players[c.PLAYERS[0]].inventory[3] = item('minecraft:bone_meal', 4)
  before.setupWrites = [{ seq: 2, action: 'arm' + kind + key, position,
    after: { sequence: 1, target: position, playerUuid: before.players[c.PLAYERS[0]].uuid, hand: 'HAND' } }]
  const after = structuredClone(before)
  after.fixture[key] = crop(key, 0)
  after.setupWrites.push({ seq: 3, action: 'applied:' + kind, position, after: { armSeq: 1 } })
  after.events.push({ seq: 4, type, position, playerUuid: kind === 'grow' ? null : after.players[c.PLAYERS[0]].uuid,
    hand: kind === 'interact' ? 'HAND' : null, cancelled: true, finalObservedAt: 100 })
  return { before, after, position, type }
}
test('a canceled crop event requires its exact target arm and ordered applied receipt', () => {
  const value = canceledBone()
  assert.equal(c.boneResult(value.before, value.after, 'tomato', value.position, c.PLAYERS[0], 'grow').consumed, 0)
  for (const [name, mutate] of Object.entries({
    'wheat stole the one-shot fault': v => { v.after.setupWrites[1].position = c.POSITIONS.wheat },
    'wheat canceled event cannot credit tomato': v => { v.after.events[0].position = c.POSITIONS.wheat },
    'unrelated earlier arm': v => { v.after.setupWrites[1].after.armSeq = 99 },
    'no prior targeted arm': v => { v.before.setupWrites[0].position = null },
    'callback not applied': v => { v.after.setupWrites.pop() },
    'unsettled event': v => { v.after.events[0].finalObservedAt = null },
    'uncanceled event': v => { v.after.events[0].cancelled = false },
    'reversed causality': v => { v.after.events[0].seq = 2 }
  })) {
    const changed = structuredClone(value); mutate(changed)
    assert.throws(() => c.boneResult(changed.before, changed.after, 'tomato', changed.position, c.PLAYERS[0], 'grow'), name)
  }
})
test('player-bearing cancellation receipts require the acting player and observed interaction hand', () => {
  const value = canceledBone('interact')
  assert.equal(c.boneResult(value.before, value.after, 'tomato', value.position, c.PLAYERS[0], 'interact').consumed, 0)
  for (const [name, mutate] of Object.entries({
    'another player interaction': v => { v.after.events[0].playerUuid = v.before.players[c.PLAYERS[1]].uuid },
    'another interaction hand': v => { v.after.events[0].hand = 'OFF_HAND' },
    'additional uncanceled interaction receipt': v => { v.after.events.push({ ...v.after.events[0], seq: 5, cancelled: false }) }
  })) {
    const changed = structuredClone(value); mutate(changed)
    assert.throws(() => c.boneResult(changed.before, changed.after, 'tomato', changed.position,
      c.PLAYERS[0], 'interact'), name)
  }

  // BlockFertilizeEvent carries a player but has no hand field; preserve the
  // actor check without pretending the native event reports a hand.
  const fertilizer = canceledBone('fertilize')
  assert.equal(c.boneResult(fertilizer.before, fertilizer.after, 'tomato', fertilizer.position,
    c.PLAYERS[0], 'fertilize').consumed, 0)
  fertilizer.after.events[0].playerUuid = fertilizer.before.players[c.PLAYERS[1]].uuid
  assert.throws(() => c.boneResult(fertilizer.before, fertilizer.after, 'tomato', fertilizer.position,
    c.PLAYERS[0], 'fertilize'), /another player/)
  const wrongFertilizerHand = canceledBone('fertilize')
  wrongFertilizerHand.before.setupWrites[0].after.hand = 'OFF_HAND'
  assert.throws(() => c.boneResult(wrongFertilizerHand.before, wrongFertilizerHand.after, 'tomato',
    wrongFertilizerHand.position, c.PLAYERS[0], 'fertilize'), /another hand/)
  const duplicateFertilizer = canceledBone('fertilize')
  duplicateFertilizer.after.events.push({ ...duplicateFertilizer.after.events[0], seq: 5, cancelled: false })
  assert.throws(() => c.boneResult(duplicateFertilizer.before, duplicateFertilizer.after, 'tomato',
    duplicateFertilizer.position, c.PLAYERS[0], 'fertilize'), /exactly one native event/)
})
test('a fertilizer callback that never emitted cannot be labeled an executed cancellation', () => {
  const value = canceledBone('fertilize')
  c.boneResult(value.before, value.after, 'tomato', value.position, c.PLAYERS[0], 'fertilize')
  value.after.events = []; value.after.setupWrites.pop(); value.after.fixture.tomato.age = 3
  const result = c.boneResult(value.before, value.after, 'tomato', value.position, c.PLAYERS[0], 'fertilize')
  assert.equal(result.emitted, false); assert.equal(result.cancellationAvailable, false)
})
test('offhand durability normalization and bone-meal counts use the actual source hand', () => {
  const before = snapshot(), after = snapshot(), actor = c.PLAYERS[0]
  const tool = item(c.NS + 'sickle')
  before.players[actor].offHand = tool
  after.players[actor].offHand = { ...tool, damage: 1, unitSerialized: Buffer.from('offhand damage1').toString('base64'), unitSha256: sha('offhand damage1') }
  c.expectDelta(before, after, {}, { actor, hand: 1, damage: 1 })
  assert.throws(() => c.expectDelta(before, after, {}, { actor, slot: 4, damage: 1 }))
  before.players[actor].offHand = item('minecraft:bone_meal', 4)
  after.players[actor].offHand = item('minecraft:bone_meal', 3)
  after.fixture.tomato = crop('tomato', 3)
  assert.equal(c.boneResult(before, after, 'tomato', c.POSITIONS.tomato, actor, 'success', 1).consumed, 1)
})

// Execute the real exported lifecycle with protocol-level fake bots. These
// tests exercise its reads, commands, action ledger, conservation and immutable
// checkpoint files; they do not claim a live server or native resource decode.
function lifecycle (t, options = {}) {
  const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'crop-lifecycle-'))
  t.after(() => fs.rmSync(folder, { recursive: true, force: true }))
  const stateFile = path.join(folder, 'state.json'), state = snapshot(), reports = {}, commands = []
  state.world.owned = true; state.world.loaded = true
  state.epoch = 0
  state.canonicalItems['minecraft:bone_meal'] = item('minecraft:bone_meal')
  let sequence = 0, currentPhase
  class Vec3 {
    constructor (x, y, z) { Object.assign(this, { x, y, z }) }
    toArray () { return [this.x, this.y, this.z] }
  }
  const block = (key, age = null) => ({ ...(age === null ? { loaded: true, owned: true, id: null, age: null, material: 'AIR',
    maintainsFarmland: false, shape: [], collision: [], supportShape: [] } : { ...crop(cropOf(key), age), material: 'TRIPWIRE' }),
    position: c.POSITIONS[key], support: { material: 'FARMLAND', moisture: 7 }, rawBrightness: 14, skyLight: 0 })
  const cropOf = key => key.startsWith('persist') ? key.slice(7).toLowerCase() : key
  for (const key of c.PERSISTED) state.fixture[key] = block(key)
  const add = (id, amount) => {
    const inventory = state.players[c.PLAYERS[0]].inventory
    let slot = inventory.findIndex((entry, index) => index >= 9 && entry.id === id)
    if (slot === -1) slot = inventory.findIndex((entry, index) => index >= 9 && !entry.id)
    inventory[slot] = item(id, (inventory[slot].count || 0) + amount)
  }
  const receipt = (bot, position, type = 'PlayerInteractEvent', hand = 0) => {
    const event = { seq: ++sequence, type, position, playerUuid: state.players[bot.username].uuid,
      finalObservedAt: 1, hand: hand ? 'OFF_HAND' : 'HAND', cancelled: false }
    if (type === 'PlayerInteractEvent' && options.misattributedReceipt === 'actor') event.playerUuid = 'unrelated-player-uuid'
    if (type === 'PlayerInteractEvent' && options.misattributedReceipt === 'hand') event.hand = hand ? 'HAND' : 'OFF_HAND'
    state.events.push(event)
    if (type === 'PlayerInteractEvent' && options.misattributedReceipt === 'duplicate')
      state.events.push({ ...event, seq: ++sequence })
  }
  const bots = c.PLAYERS.map(username => {
    const bot = { username, entity: { position: new Vec3(728, 200, 13) }, quickBarSlot: 0, inventory: { slots: [] },
      setQuickBarSlot (slot) { this.quickBarSlot = slot; state.players[username].heldSlot = slot },
      lookAt: async () => {}, blockAt: position => ({ position }), entities: {},
      async dig (target) {
        const position = target.position.toArray(), key = c.PERSISTED.find(key => c.POSITIONS[key].every((value, i) => position[i] === value))
        assert.ok(key, 'Protocol dig targeted an unknown plant')
        assert.equal(state.fixture[key].age, 7)
        const name = cropOf(key); state.fixture[key] = block(key)
        add(c.NS + name + '_seed', options.duplicateLoot ? 5 : 1)
        add(c.NS + (name === 'chili' ? 'red_chili' : name), 1)
        receipt(bot, position, 'BlockBreakEvent')
      }
    }
    bot._client = { write (kind, packet) {
      if (kind !== 'block_place') return
      const position = packet.location.toArray(), cropPosition = [position[0], 200, position[2]]
      const key = c.PERSISTED.find(key => c.POSITIONS[key].every((value, i) => cropPosition[i] === value))
      assert.ok(key, 'Protocol use targeted an unknown plant')
      const player = state.players[username], held = player.inventory[player.heldSlot]
      if (currentPhase === 'seed' && position[1] === 199) {
        assert.equal(held.id, c.NS + cropOf(key) + '_seed'); assert.equal(state.fixture[key].id, null)
        held.count--; state.fixture[key] = block(key, 0)
      } else if (currentPhase === 'seed' && held.id === 'minecraft:bone_meal') {
        held.count--; state.fixture[key] = block(key, Math.min(7, state.fixture[key].age + 3))
      }
      receipt(bot, position, 'PlayerInteractEvent', packet.hand)
    } }
    return bot
  })
  async function run (phase) {
    currentPhase = phase; state.events = []; state.setupWrites = []
    if (phase === 'verify' && options.tamper) options.tamper(state)
    const report = reports[phase] = { checks: {} }
    const inspect = async (bot, action) => {
      commands.push({ phase, action })
      if (phase !== 'seed') assert.ok(['verifycheckpoint', 'emptycheckpoint', 'observe', 'sealverify'].includes(action))
      if (action === 'stageplant') for (const key of c.PERSISTED) state.fixture[key] = block(key, options.fixturePlanted ? 0 : null)
      if (action === 'stock' || action === 'stockempty') {
        state.players[bot.username].inventory = Array.from({ length: 36 }, () => ({}))
        if (action === 'stock') {
          c.CROPS.forEach((name, index) => { state.players[bot.username].inventory[index] = item(c.NS + name + '_seed', 64) })
          state.players[bot.username].inventory[3] = item('minecraft:bone_meal', 64)
        }
      }
      if (action.startsWith('stage') || action.startsWith('stock')) state.setupWrites.push({ seq: ++sequence, action })
      if (action === 'stagelight8') for (const key of c.PERSISTED) state.fixture[key].rawBrightness = 8
      const value = structuredClone(state)
      Object.assign(value, { schema: 'source-crops-v1', phase, runId: path.basename(folder), actor: bot.username, action, errors: [] })
      if (action.includes('checkpoint')) value.checkpoint = { created: true, matched: true, differences: [] }
      if (action === 'sealverify') value.checkpoint = { created: true }
      return value
    }
    await scenario({ phase, stateFile, out: path.join(folder, phase), report, a: bots[0], b: bots[1], inspect, Vec3,
      delay: async () => {}, walkTo: async (bot, position) => { bot.entity.position = position },
      until: async (description, fn) => { for (let i = 0; i < 10; i++) { const value = await fn(); if (value) return value } throw new Error('Test wait failed: ' + description) } })
    return report.checks.sourceCrops
  }
  return { run, state, stateFile, commands, reports }
}
test('the actual seed/recovery/empty path plants six seeds, matures, recovers and never resurrects', async t => {
  const value = lifecycle(t)
  const seeded = await value.run('seed'), seedBytes = fs.readFileSync(value.stateFile)
  assert.equal(seeded.groups.restartSeed.realPlantedCrops, 6)
  assert.equal(seeded.cases.filter(entry => entry.caseId.startsWith('restart-seed-plant-')).length, 6)
  assert.equal(seeded.cases.filter(entry => entry.caseId.startsWith('restart-seed-bone-')).length, 18)
  const verified = await value.run('verify'), verifiedBytes = fs.readFileSync(value.stateFile + '.verified.json')
  assert.equal(verified.cases.length, 6)
  const empty = await value.run('empty')
  assert.equal(empty.groups.noResurrection.status, 'PASS')
  assert.deepEqual(fs.readFileSync(value.stateFile), seedBytes)
  assert.deepEqual(fs.readFileSync(value.stateFile + '.verified.json'), verifiedBytes)
  assert.ok(value.commands.filter(entry => entry.phase !== 'seed').every(entry => !entry.action.startsWith('stage') && !entry.action.startsWith('stock')))
})
for (const attribution of ['actor', 'hand', 'duplicate']) test('the full seed lifecycle rejects a protocol receipt with ' + attribution + ' evidence', async t => {
  const value = lifecycle(t, { misattributedReceipt: attribution })
  await assert.rejects(value.run('seed'), /source crop action|duplicate native action/i)
})
for (const [name, options, expected] of [
  ['fixture-created persistent crops', { fixturePlanted: true }, /null/],
  ['post-restart crop age drift', { tamper: state => { state.fixture.persistTomato.age = 6 } }, /Persistent crop/],
  ['post-restart offhand loss', { tamper: state => { state.players[c.PLAYERS[1]].offHand = item(c.NS + 'tomato') } }, /Persistent player/],
  ['post-restart fixture writes', { tamper: state => { state.setupWrites.push({ seq: 10000, action: 'secret-stage' }) } }, /fixture or fault writes/],
  ['duplicate recovery products', { duplicateLoot: true }, /outside/]
]) test('actual lifecycle rejects ' + name, async t => {
  const value = lifecycle(t, options)
  if (options.fixturePlanted) await assert.rejects(value.run('seed'), expected)
  else { await value.run('seed'); await assert.rejects(value.run('verify'), expected) }
})

function coreLock () {
  return { schema: 'source-crops-core-packs-v1', reloads: [], workflows: [],
    observations: [{ sequence: 1, sha256: sha('initial'), bytes: 7, workflow: 0 }] }
}
function coreRound (lock, round) {
  const issuedAtMillis = Date.now(), checksum = sha('workflow-' + round)
  lock.reloads.push({ sequence: round, command: 'ce reload config', beforeEpoch: round - 1, afterEpoch: round,
    issuedAtMillis: issuedAtMillis - 10, expiresAtMillis: issuedAtMillis - 10 + 90000, completedAtMillis: issuedAtMillis - 5 })
  lock.workflows.push({ sequence: round, command: 'ce workflow default', beforeSha256: lock.observations.at(-1).sha256,
    afterSha256: checksum, issuedAtMillis, expiresAtMillis: issuedAtMillis + 240000,
    completedAtMillis: issuedAtMillis + 1, completionLog: 'Resource pack workflow default completed' })
  lock.observations.push({ sequence: round + 1, sha256: checksum, bytes: 10, workflow: round })
}
test('core pack matching follows actual hosted bytes and fixed reload/workflow windows', () => {
  const lock = coreLock()
  assert.deepEqual(c.matchCorePack(lock, sha('initial'), 7, 0), { workflow: 0, observation: 1 })
  coreRound(lock, 1)
  assert.deepEqual(c.matchCorePack(lock, sha('workflow-1'), 10, 1), { workflow: 1, observation: 2 })
  assert.equal(c.completedCoreWorkflow(lock, 1), lock.workflows[0])
  assert.equal(c.matchCorePack(lock, sha('initial'), 7, 1), false)
  for (const mutate of [value => { value.reloads[0].afterEpoch = 9 },
    value => { value.reloads[0].completedAtMillis = value.workflows[0].issuedAtMillis + 1 },
    value => { value.workflows[0].expiresAtMillis++ },
    value => { value.workflows[0].completedAtMillis = value.workflows[0].expiresAtMillis + 1 }]) {
    const changed = structuredClone(lock); mutate(changed)
    assert.throws(() => c.completedCoreWorkflow(changed, 1))
  }
  assert.throws(() => c.matchCorePack(lock, sha('workflow-1'), 10, 3))
})
for (const fault of [null, 'shape', 'epoch', 'missing-reoffer']) test('actual core prefix executes exactly two native rounds' + (fault ? ' and rejects ' + fault : ''), async t => {
  const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'crop-core-'))
  t.after(() => fs.rmSync(folder, { recursive: true, force: true }))
  const out = path.join(folder, 'core'); fs.mkdirSync(out)
  const state = snapshot(), lock = coreLock(), report = { checks: {}, packHistory: Object.fromEntries(c.PLAYERS.map(name => [name, [{ packSha256: sha('initial'), workflow: 0 }]])) }
  const lockFile = path.join(out, 'resource-pack-lock.json'), commands = []
  fs.writeFileSync(lockFile, JSON.stringify(lock))
  let epoch = 0, rounds = 0
  const bot = { username: c.PLAYERS[0] }, sentinel = new Error('Core prefix complete before gameplay setup')
  const inspect = async (actor, action) => {
    if (action === 'stageplant') throw sentinel
    state.fixture.ages = c.CROPS.flatMap(name => Array.from({ length: 8 }, (_, age) => crop(name, age)))
    if (fault === 'shape' && action === 'shapes') state.fixture.ages[0].shape = []
    return { ...structuredClone(state), schema: 'source-crops-v1', phase: 'core', runId: path.basename(folder),
      actor: actor.username, action, errors: [], epoch, world: { ...state.world, owned: true, loaded: true } }
  }
  await assert.rejects(scenario({ phase: 'core', out, stateFile: path.join(folder, 'state.json'), report, a: bot,
    b: { username: c.PLAYERS[1] }, inspect,
    until: async (description, fn) => { const value = await fn(); if (!value) throw new Error('Test wait failed: ' + description); return value },
    consoleCommand: command => {
      commands.push(command)
      if (command === 'ce reload config') epoch += fault === 'epoch' ? 2 : 1
      else {
        coreRound(lock, ++rounds); fs.writeFileSync(lockFile, JSON.stringify(lock))
        if (fault !== 'missing-reoffer') for (const name of c.PLAYERS) report.packHistory[name].push({ workflow: rounds, packSha256: lock.workflows.at(-1).afterSha256 })
      }
    }
  }), error => fault ? error !== sentinel : error === sentinel)
  if (!fault) {
    assert.deepEqual(commands, ['ce reload config', 'ce workflow default', 'ce reload config', 'ce workflow default'])
    assert.equal(report.checks.sourceCrops.groups.reload.genuineEpochIncrements, 2)
    assert.equal(report.checks.sourceCrops.cases.filter(entry => entry.scope === 'LIFECYCLE').length, 2)
    assert.ok(c.PLAYERS.every(name => report.packHistory[name].length === 3))
  } else assert.ok(commands.length < 4)
})

test('actual pack receiver saves each downloaded offer and waits for its matching native reader', async t => {
  const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'crop-pack-receiver-'))
  t.after(() => fs.rmSync(folder, { recursive: true, force: true }))
  const source = fs.readFileSync(path.join(__dirname, 'players.cjs'), 'utf8')
  const receiveSource = source.slice(source.indexOf('async function receivePack ('), source.indexOf('async function connect ('))
  const lock = coreLock(), statuses = [], readers = [], report = { packs: {} }, cropOffers = {}
  let data = Buffer.from('initial')
  const saveLock = () => fs.writeFileSync(path.join(folder, 'resource-pack-lock.json'), JSON.stringify(lock))
  saveLock()
  const context = { assert, fs, path, createHash, Buffer, URL, AbortSignal, Date, require, __dirname,
    scenario: 'sourcecrops', phase: 'core', out: folder, packSha256: sha(data), cropOffers, report,
    shutdown: new AbortController(), cropResourceReader: Promise.resolve(),
    process: { env: { SENLUO_CLIENT_CLASSPATH: 'official-client-fixture' } },
    fetch: async () => ({ status: 200, arrayBuffer: async () => data }),
    until: async (description, fn) => { const value = await fn(); assert.ok(value, description); return value },
    runReader: async (_java, args) => {
      const inputs = fs.readFileSync(args[0].slice(1), 'utf8').split('\n').map(line => JSON.parse(line))
      const archive = fs.readFileSync(inputs[7])
      readers.push(path.basename(inputs[7]))
      fs.writeFileSync(inputs[6], JSON.stringify([{ packSha256: sha(archive), client: '1.21.11', assetsRead: 4 }]))
    }
  }
  vm.createContext(context); vm.runInContext(receiveSource + ';this.receive = receivePack', context)
  const bot = { _client: { uuid: 'test-player', write: (type, value) => { assert.equal(type, 'resource_pack_receive'); statuses.push(value.result) } } }
  for (let round = 0; round <= 2; round++) {
    if (round) { coreRound(lock, round); data = Buffer.from('workflow-' + round); saveLock() }
    await context.receive(bot, c.PLAYERS[0], { uuid: 'offer-' + round, url: 'http://127.0.0.1/pack.zip', hash: createHash('sha1').update(data).digest('hex') })
  }
  assert.deepEqual(statuses, [3, 4, 0, 3, 4, 0, 3, 4, 0])
  assert.deepEqual(readers, [1, 2, 3].map(number => c.PLAYERS[0] + '-offer-' + number + '.zip'))
  assert.deepEqual(Array.from(report.packHistory[c.PLAYERS[0]], entry => entry.workflow), [0, 1, 2])
  assert.ok(report.packHistory[c.PLAYERS[0]].every(entry => entry.readerCompletedAtMillis >= entry.downloadedAtMillis))
  await assert.rejects(context.receive(bot, c.PLAYERS[0], { uuid: 'extra', url: 'http://127.0.0.1/pack.zip', hash: createHash('sha1').update(data).digest('hex') }), /beyond two/)
  context.phase = 'seed'
  await assert.rejects(context.receive(bot, c.PLAYERS[1], { uuid: 'drift', url: 'http://127.0.0.1/pack.zip', hash: createHash('sha1').update(data).digest('hex') }))
})

test('natural cohorts require measured dry/wet soil, native light and the real dense diagonal', () => {
  const environment = layout => ({ fixture: Object.fromEntries(c.CROPS.map(name => [name, {
    rawBrightness: 9, skyLight: 0, support: { material: 'FARMLAND', moisture: layout === 'stagedry' ? 0 : 7 },
    soil: [-1, 0, 1].flatMap(dx => [-1, 0, 1].map(dz => ({ position: [c.POSITIONS[name][0] + dx, 199, c.POSITIONS[name][2] + dz],
      loaded: true, owned: true, material: 'FARMLAND', moisture: layout === 'stagedry' ? 0 : 7,
      cropAbove: { id: layout === 'stagedense' && dx === -1 && dz === 1 ? c.NS + name + '_crop' : null } })))
  }])) })
  for (const layout of ['stagewet', 'stagedry', 'stagedense']) c.assertNaturalEnvironment(environment(layout), layout)
  for (const mutate of [value => { value.fixture.tomato.rawBrightness = 8 },
    value => { value.fixture.tomato.skyLight = 1 }, value => { value.fixture.tomato.support.moisture = 7 },
    value => { value.fixture.tomato.soil.pop() }, value => { value.fixture.tomato.soil[0].owned = false },
    value => { value.fixture.tomato.soil[0].moisture = 7 }]) {
    const value = environment('stagedry'); mutate(value); assert.throws(() => c.assertNaturalEnvironment(value, 'stagedry'))
  }
  assert.throws(() => c.assertNaturalEnvironment(environment('stagewet'), 'stagedense'))
})
