'use strict'
// Contract checks only: no Minecraft server, protocol client, or external package is started.
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { createHash } = require('node:crypto')
const scenario = require('./persistence-players.cjs')

const IDS = {
  sofa: 'kaleidoscope_deco:kaleidoscope_bar/sofa',
  doll: 'kaleidoscope_doll:doll_0',
  tub: 'kaleidoscope_tavern:pressing_tub'
}
const BERRIES = 'minecraft:glow_berries'
const clone = value => structuredClone(value)
const source = (id, pdc = {}) => ({ id, count: 1, pdc, serialized: Buffer.from(id).toString('base64') })
const inventoryItem = ({ serialized, ...item }) => clone(item)

function fixture (t, phase = 'empty', { protocol = false } = {}) {
  const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'senluo-persistence-contract-'))
  const out = path.join(folder, phase)
  fs.mkdirSync(out)
  fs.writeFileSync(path.join(out, 'fixture.json'), JSON.stringify({ port: 25565, packSha256: 'contract-only' }))
  const stateFile = path.join(folder, 'persistence-state.json')
  const berry = source(BERRIES, { 'senluo_acceptance:restart_marker': { string: 'three-phase-pressing-tub-v1' } })
  const seed = { schemaVersion: 1, phase: 'seed', fixtures: {}, players: {
    A: { uuid: 'player-a', inventory: [{ id: 'minecraft:red_dye', count: 2 }] },
    B: { uuid: 'player-b', inventory: [] }
  } }
  for (const [index, name] of Object.keys(IDS).entries()) {
    seed.fixtures[name] = {
      present: true, id: IDS[name], uuid: '00000000-0000-0000-0000-' + String(index).padStart(12, '0'),
      world: 'acceptance-world', position: [678.5 + 2 * index, 200, 9.5], hitbox: 200 + index,
      variant: name === 'sofa' ? IDS.sofa.replace(':', '/') + '/style/red/left' : 'ground',
      source: name === 'tub' ? {} : source(IDS[name], {
        [name === 'sofa' ? 'kaleidoscopedecoplugin:deco_state_v1' : 'kaleidoscopedollplugin:pose_v1']: { bytes: 'c2F2ZWQ=' }
      }),
      saved: name + '-saved-controller-data', blocked: false, hasContents: name === 'tub',
      state: name === 'sofa' ? { state: IDS.sofa, color: 'red', mode: 'left' }
        : name === 'doll' ? { pose: '1.1,1.1,1.1,0.05,0.05,0.05,15,15,15' }
          : { amount: 0, ingredients: berry }
    }
  }
  const empty = {
    sofa: { present: false, world: 'acceptance-world', position: clone(seed.fixtures.sofa.position) },
    doll: { present: false, world: 'acceptance-world', position: clone(seed.fixtures.doll.position) },
    tub: { ...clone(seed.fixtures.tub), hasContents: false, saved: 'tub-empty-controller-data', state: { amount: 0, ingredients: {} } },
    looseItems: []
  }
  const observed = phase === 'empty' ? empty : { ...clone(seed.fixtures), looseItems: [] }
  const inventories = phase === 'empty'
    ? { A: [...clone(seed.players.A.inventory), inventoryItem(seed.fixtures.sofa.source)],
        B: [inventoryItem(seed.fixtures.doll.source), inventoryItem(berry)] }
    : { A: clone(seed.players.A.inventory), B: [] }
  fs.writeFileSync(stateFile, JSON.stringify(seed))
  const digest = () => createHash('sha256').update(fs.readFileSync(stateFile)).digest('hex')
  const originalDigest = digest()
  const reads = [], actions = [], delays = []
  const networkAction = name => async () => {
    actions.push(name)
    throw new Error('Unexpected protocol mutation before contract validation: ' + name)
  }
  const bot = username => ({ username, lookAt: networkAction('lookAt'), setQuickBarSlot: networkAction('setQuickBarSlot'),
    setControlState: networkAction('setControlState'), attack: networkAction('attack'), closeWindow: networkAction('closeWindow'),
    _client: { write: networkAction('packet') } })
  const harness = {
    a: bot('A'), b: bot('B'), out, phase, stateFile, report: { checks: {} },
    inspect: async (player, action) => {
      reads.push({ player: player.username, action })
      return { playerUuid: seed.players[player.username].uuid, inventory: clone(inventories[player.username]),
        persistence: clone(observed), drops: [] }
    },
    delay: async ms => { delays.push(ms) },
    click: networkAction('click'),
    until: networkAction('until'),
    Vec3: class Vec3 {},
    count: (items, id) => items.filter(item => item.id === id).reduce((sum, item) => sum + item.count, 0)
  }
  t.after(() => {
    assert.equal(digest(), originalDigest, 'A restart verification phase rewrote the seed checkpoint')
    assert.ok(reads.every(read => read.action === 'persistinspect'), 'A restart phase arranged or reset a fixture')
    if (!protocol) assert.deepEqual(actions, [], 'Contract checks must fail before protocol mutations')
    // The only recursive removal is the exact unique directory created by this test.
    assert.equal(path.dirname(path.resolve(folder)), path.resolve(os.tmpdir()))
    fs.rmSync(folder, { recursive: true, force: true })
  })
  return { harness, seed, observed, inventories, reads, delays, actions }
}

function loadMovementHelpers (bindings) {
  const code = fs.readFileSync(path.join(__dirname, 'players.cjs'), 'utf8').replace(/\r\n/g, '\n')
  const names = ['observeSnapshot', 'actAndObserve', 'useAndObserve', 'selectHotbar', 'adjustDollAxes', 'boardTulipGain', 'harvestTulipConcurrently', 'collectBoardTulip',
    'walkTo', 'parkFurniturePeer', 'returnFurniturePeer', 'pickUpFurniture']
  const functions = names.map(name => {
    const start = code.indexOf((name === 'boardTulipGain' ? 'function ' : 'async function ') + name + ' (')
    assert.ok(start >= 0)
    const following = /\n(?:async )?function /.exec(code.slice(start + 1))
    const end = following ? start + 1 + following.index : -1
    assert.ok(end > start)
    return code.slice(start, end)
  }).join('\n')
  // Compile only dependency-free helpers; never execute the protocol entrypoint or load Mineflayer.
  return new Function(...Object.keys(bindings), functions + '\nreturn { ' + names.join(', ') + ' }')(...Object.values(bindings))
}

function verifyFixture (t, { duplicateReturn = false, staleEmptyHand = false, helperResult, duplicateDrop = false,
  otherPlayerPickup = false, duplicatePlayerPickup = false, wrongOtherPdc = false } = {}) {
  const f = fixture(t, 'verify', { protocol: true })
  const { harness, observed, inventories, seed, actions } = f
  const selected = { A: 0, B: 0 }, attacks = {}, pending = [], drops = [], tubUses = [], pickupCalls = []
  const interactions = { A: 0, B: 0 }
  let returnedSlot, invalidateSelection = false
  const put = (username, item) => {
    const inventory = inventories[username]
    while (inventory.length < 36) inventory.push({})
    const slot = inventory.findIndex(value => !value.id)
    assert.ok(slot >= 0, 'The simulated player inventory is full')
    inventory[slot] = inventoryItem(item)
    return slot
  }
  const collect = bot => {
    for (let index = pending.length - 1; index >= 0; index--) {
      const reward = pending[index]
      if (Math.hypot(bot.entity.position.x - reward.position[0], bot.entity.position.z - reward.position[2]) < .85) {
        put(bot.username, reward.item)
        for (const player of [harness.a, harness.b]) delete player.entities[reward.entityId]
        pending.splice(index, 1)
      }
    }
    observed.looseItems = pending.map(reward => clone(reward.item))
  }
  harness.Vec3 = class Vec3 {
    constructor (x, y, z) { Object.assign(this, { x, y, z }) }
  }
  for (const bot of [harness.a, harness.b]) {
    bot.entity = { position: { x: 680.5, y: 200, z: 9.5 } }
    bot.entities = Object.fromEntries(Object.entries(seed.fixtures).map(([name, value]) => [value.hitbox, { name }]))
    bot.supportFeature = key => { assert.equal(key, 'metadataIxOfItem'); return 8 }
    bot.lookAt = async target => { bot.target = target; actions.push('lookAt') }
    bot.setQuickBarSlot = slot => {
      assert.ok(Number.isInteger(slot) && slot >= 0 && slot < 9)
      selected[bot.username] = slot
      actions.push('setQuickBarSlot')
      if (staleEmptyHand && tubUses.length === 1 && bot.username === 'B') invalidateSelection = true
    }
    bot.setControlState = (control, enabled) => {
      actions.push(control + ':' + enabled)
      if (control === 'sneak') bot.sneaking = enabled
      if (control === 'forward' && enabled) {
        bot.entity.position.x = bot.target.x
        bot.entity.position.z = bot.target.z
        collect(bot)
      }
    }
    bot.attack = entity => {
      actions.push('attack:' + entity.name)
      assert.ok(['sofa', 'doll'].includes(entity.name))
      attacks[entity.name] = (attacks[entity.name] || 0) + 1
      if (attacks[entity.name] === 3) {
        assert.equal(observed[entity.name].present, true)
        observed[entity.name].present = false
        const item = inventoryItem(seed.fixtures[entity.name].source)
        drops.push(item)
        if (duplicateDrop) drops.push(clone(item))
        const [x, y, z] = seed.fixtures[entity.name].position
        const position = [x - 1.4, y, z + .8], entityId = 500 + drops.length
        pending.push({ position, item, entityId })
        const networkDrop = {
          id: entityId, name: 'item', position: new harness.Vec3(...position), metadata: { 8: { itemCount: 1 } },
          getDroppedItem: () => ({ count: 1, components: [{ type: 'custom_data', data: { type: 'compound',
            value: { 'craftengine:id': { type: 'string', value: item.id } } } }] })
        }
        for (const player of [harness.a, harness.b]) player.entities[entityId] = networkDrop
        if (otherPlayerPickup && entity.name === 'sofa') {
          const slot = put('B', item)
          if (wrongOtherPdc) inventories.B[slot].pdc = { 'wrong:state': { bytes: 'wrong' } }
          if (duplicatePlayerPickup) put('A', item)
          pending.pop()
          for (const player of [harness.a, harness.b]) delete player.entities[entityId]
        }
        observed.looseItems = pending.map(reward => clone(reward.item))
      }
    }
    bot._client.write = (packet, value) => {
      actions.push(packet)
      assert.equal(packet, 'use_entity')
      assert.equal(bot.username, 'B')
      assert.equal(value.target, observed.tub.hitbox)
      assert.equal(value.sneaking, true)
      assert.equal(bot.sneaking, true)
      interactions[bot.username]++
      const slot = selected.B, held = inventories.B[slot]
      tubUses.push({ slot, held: clone(held), hadContents: observed.tub.hasContents })
      if (held.id === BERRIES) {
        // Match the live failure: returning into the selected slot makes the next use insert it again.
        observed.tub.hasContents = true
        observed.tub.state.ingredients = clone(seed.fixtures.tub.state.ingredients)
        inventories.B[slot] = {}
      } else {
        assert.deepEqual(held, {}, 'The tub return must use a verified empty hand')
        if (observed.tub.hasContents || duplicateReturn) {
          returnedSlot = put('B', seed.fixtures.tub.state.ingredients)
          observed.tub.hasContents = false
          observed.tub.state.ingredients = {}
        }
      }
    }
  }
  const inspect = harness.inspect
  harness.inspect = async (bot, action) => {
    if (invalidateSelection) {
      inventories.B[selected.B] = { id: 'minecraft:stone', count: 1 }
      invalidateSelection = false
    }
    return { ...await inspect(bot, action), heldSlot: selected[bot.username], drops: clone(drops),
      furnitureInteractions: interactions[bot.username] }
  }
  harness.until = async (description, predicate) => {
    for (let attempt = 0; attempt < 4; attempt++) {
      const result = await predicate()
      if (result) return result
    }
    assert.fail(description)
  }
  const delay = harness.delay
  harness.delay = async ms => {
    await delay(ms)
    if (invalidateSelection) {
      inventories.B[selected.B] = { id: 'minecraft:stone', count: 1 }
      invalidateSelection = false
    }
  }
  const helpers = loadMovementHelpers({ assert, until: harness.until, delay: harness.delay,
    inspect: harness.inspect, count: harness.count, Vec3: harness.Vec3 })
  harness.observeSnapshot = helpers.observeSnapshot
  harness.useAndObserve = helpers.useAndObserve
  harness.adjustDollAxes = helpers.adjustDollAxes
  harness.pickUpFurniture = async (bot, id, broken, origin, description, read, participants) => {
    pickupCalls.push({ player: bot.username, id, origin: [origin.x, origin.y, origin.z], participants: participants.map(player => player.username) })
    if (helperResult === 'empty') return null
    const picked = await helpers.pickUpFurniture(bot, id, broken, origin, description, read, participants)
    if (helperResult === 'wrongPdc') picked.snapshot.inventory[picked.slot].pdc = { 'wrong:state': { bytes: 'wrong' } }
    return picked
  }
  return { ...f, tubUses, selected, pickupCalls, returnedSlot: () => returnedSlot }
}

test('verify recovers all fixtures and reselects an empty hand after the berry fills the first hand slot', async t => {
  const f = verifyFixture(t)
  await scenario(f.harness)
  assert.equal(f.tubUses.length, 2)
  assert.deepEqual(f.tubUses.map(use => use.held), [{}, {}])
  assert.deepEqual(f.tubUses.map(use => use.hadContents), [true, false])
  assert.equal(f.returnedSlot(), f.tubUses[0].slot, 'The first return must fill the selected empty slot')
  assert.notEqual(f.tubUses[1].slot, f.tubUses[0].slot, 'The repeated return must select another empty slot')
  assert.equal(f.observed.tub.hasContents, false)
  assert.equal(f.harness.count(f.inventories.B, BERRIES), 1)
  assert.deepEqual(f.inventories.B.find(item => item.id === BERRIES).pdc, f.seed.fixtures.tub.state.ingredients.pdc)
  assert.equal(f.harness.report.checks.persistenceVerify.sofa.dropCount, 1)
  assert.equal(f.harness.report.checks.persistenceVerify.doll.dropCount, 1)
  assert.equal(f.harness.report.checks.persistenceVerify.returnedBerries, 1)
  assert.equal(f.harness.report.checks.persistenceVerify.repeatedReturn, 0)
  assert.equal(f.selected.B, f.tubUses[1].slot)
  assert.ok(f.actions.includes('sneak:false'), 'The player must stop sneaking after the check')
  assert.deepEqual(f.pickupCalls, [
    { player: 'A', id: IDS.sofa, origin: f.seed.fixtures.sofa.position, participants: ['A', 'B'] },
    { player: 'B', id: IDS.doll, origin: f.seed.fixtures.doll.position, participants: ['A', 'B'] }
  ], 'Restart recovery must use the same real network-drop helper for both furniture items')
})

test('verify rejects an empty result from its required network-drop pickup helper', async t => {
  const f = verifyFixture(t, { helperResult: 'empty' })
  await assert.rejects(scenario(f.harness), /pickup must return an observed hotbar item/)
  assert.equal(f.pickupCalls.length, 1)
  assert.equal(f.tubUses.length, 0)
})

test('verify rejects a pickup-helper observation with different source PDC', async t => {
  const f = verifyFixture(t, { helperResult: 'wrongPdc' })
  await assert.rejects(scenario(f.harness), /recovery lost its source PDC/)
  assert.equal(f.pickupCalls.length, 1)
  assert.equal(f.tubUses.length, 0)
})

test('verify rejects duplicate furniture drops before invoking network pickup', async t => {
  const f = verifyFixture(t, { duplicateDrop: true })
  await assert.rejects(scenario(f.harness), /must produce exactly one drop/)
  assert.equal(f.pickupCalls.length, 0)
  assert.equal(f.tubUses.length, 0)
})

test('verify accepts the peer naturally collecting the unique sofa and records its actual owner', async t => {
  const f = verifyFixture(t, { otherPlayerPickup: true })
  await scenario(f.harness)
  assert.equal(f.harness.report.checks.persistenceVerify.sofa.pickedUpBy, 'B')
  assert.equal(f.harness.report.checks.persistenceVerify.doll.pickedUpBy, 'B')
  assert.equal(f.harness.count(f.inventories.A, IDS.sofa), 0)
  assert.equal(f.harness.count(f.inventories.B, IDS.sofa), 1)
  assert.deepEqual(f.inventories.B.find(item => item.id === IDS.sofa).pdc, f.seed.fixtures.sofa.source.pdc)
  assert.equal(f.tubUses.length, 2)
})

test('verify rejects one sofa in each participant inventory instead of treating either pickup as success', async t => {
  const f = verifyFixture(t, { otherPlayerPickup: true, duplicatePlayerPickup: true })
  await assert.rejects(scenario(f.harness), /must exist exactly once across its participants/)
  assert.equal(f.harness.count(f.inventories.A, IDS.sofa), 1)
  assert.equal(f.harness.count(f.inventories.B, IDS.sofa), 1)
  assert.equal(f.tubUses.length, 0)
})

test('verify rejects a peer-collected sofa whose state PDC differs from the unique drop', async t => {
  const f = verifyFixture(t, { otherPlayerPickup: true, wrongOtherPdc: true })
  await assert.rejects(scenario(f.harness), /lost its state PDC/)
  assert.equal(f.tubUses.length, 0)
})

test('verify reads back the repeated empty-hand selection and rejects a slot filled before use', async t => {
  const f = verifyFixture(t, { staleEmptyHand: true })
  await assert.rejects(scenario(f.harness), { code: 'ERR_ASSERTION' })
  assert.equal(f.tubUses.length, 1, 'A stale hand selection must fail before another tub interaction')
  assert.ok(f.actions.includes('sneak:false'))
})

test('verify still rejects a duplicate berry returned by the repeated empty-hand request', async t => {
  const f = verifyFixture(t, { duplicateReturn: true })
  await assert.rejects(scenario(f.harness), { code: 'ERR_ASSERTION' })
  assert.equal(f.tubUses.length, 2)
  assert.equal(f.observed.tub.hasContents, false)
  assert.equal(f.harness.count(f.inventories.B, BERRIES), 2)
  assert.equal(f.harness.report.checks.persistenceVerify, undefined)
})

test('empty restart accepts preserved metadata and exact holdings using read-only observations', async t => {
  const f = fixture(t)
  await scenario(f.harness)
  assert.equal(f.reads.length, 4)
  assert.deepEqual(f.delays, [2000])
  assert.equal(f.harness.report.checks.persistenceEmpty.fixtures.tub.uuid, f.seed.fixtures.tub.uuid)
  assert.deepEqual(f.harness.report.checks.persistenceEmpty.fixtures.tub.source, {}, 'An empty API-created tub may have no source item')
  const recorded = JSON.parse(fs.readFileSync(path.join(f.harness.out, 'fixture.json'), 'utf8'))
  assert.deepEqual(recorded.persistence.tub.position, f.observed.tub.position)
  assert.equal(recorded.persistence.tub.uuid, f.seed.fixtures.tub.uuid)
})

test('empty restart rejects a second returned berry', async t => {
  const f = fixture(t)
  f.inventories.B.find(item => item.id === BERRIES).count = 2
  await assert.rejects(scenario(f.harness), { code: 'ERR_ASSERTION' })
})

test('empty restart rejects returned metadata loss', async t => {
  const f = fixture(t)
  delete f.inventories.B.find(item => item.id === BERRIES).pdc
  await assert.rejects(scenario(f.harness), { code: 'ERR_ASSERTION' })
})

test('empty restart rejects a recovered furniture item with different PDC', async t => {
  const f = fixture(t)
  f.inventories.A.find(item => item.id === IDS.sofa).pdc['kaleidoscopedecoplugin:deco_state_v1'].bytes = 'Y2hhbmdlZA=='
  await assert.rejects(scenario(f.harness), { code: 'ERR_ASSERTION' })
})

test('empty restart rejects a resurrected sofa', async t => {
  const f = fixture(t)
  f.observed.sofa = clone(f.seed.fixtures.sofa)
  await assert.rejects(scenario(f.harness), /reappeared/)
})

test('empty restart rejects a different tub UUID', async t => {
  const f = fixture(t)
  f.observed.tub.uuid = '00000000-0000-0000-0000-999999999999'
  await assert.rejects(scenario(f.harness), /original furniture/)
})

test('empty restart rejects an unloadable controller instead of treating it as empty', async t => {
  const f = fixture(t)
  f.observed.tub.blocked = true
  await assert.rejects(scenario(f.harness), /frozen/)
})

test('empty restart rejects leftover world drops', async t => {
  const f = fixture(t)
  f.observed.looseItems.push({ id: BERRIES, count: 1 })
  await assert.rejects(scenario(f.harness), /Uncollected or duplicated/)
})

test('empty restart observes again and rejects a delayed revival', async t => {
  const f = fixture(t)
  f.harness.delay = async ms => {
    assert.equal(ms, 2000)
    f.observed.doll = clone(f.seed.fixtures.doll)
  }
  await assert.rejects(scenario(f.harness), /reappeared/)
  assert.equal(f.reads.length, 4)
})

for (const [property, mutate] of [
  ['uuid', value => { value.uuid = '00000000-0000-0000-0000-999999999999' }],
  ['position', value => { value.position[0] += .05 }],
  ['source', value => { value.source.serialized = 'changed-source-item' }],
  ['source PDC', value => { value.source.pdc['kaleidoscopedecoplugin:deco_state_v1'].bytes = 'changed-state' }],
  ['state', value => { value.state.color = 'blue' }],
  ['saved', value => { value.saved = 'changed-controller-data' }]
]) {
  test('verify rejects changed ' + property + ' before any player mutation', async t => {
    const f = fixture(t, 'verify')
    mutate(f.observed.sofa)
    await assert.rejects(scenario(f.harness), /changed across the server restart/)
  })
}

test('verify rejects changed offline inventory before any player mutation', async t => {
  const f = fixture(t, 'verify')
  f.inventories.A[0].count = 3
  await assert.rejects(scenario(f.harness), /Seed inventory changed while offline/)
})

test('restart contract rejects an unsupported checkpoint schema', async t => {
  const f = fixture(t)
  f.seed.schemaVersion = 2
  // Return a modified read without changing the actual immutable seed checkpoint.
  const read = fs.readFileSync
  t.mock.method(fs, 'readFileSync', (file, ...args) => file === f.harness.stateFile
    ? JSON.stringify(f.seed) : read(file, ...args))
  await assert.rejects(scenario(f.harness), { code: 'ERR_ASSERTION' })
  t.mock.restoreAll()
})

for (const name of ['sofa', 'doll']) {
  test('restart rejects a stateful ' + name + ' checkpoint without a source item', async t => {
    const f = fixture(t)
    f.seed.fixtures[name].source = {}
    const read = fs.readFileSync
    t.mock.method(fs, 'readFileSync', (file, ...args) => file === f.harness.stateFile
      ? JSON.stringify(f.seed) : read(file, ...args))
    await assert.rejects(scenario(f.harness), new RegExp('Persisted ' + name + ' source item is missing'))
    t.mock.restoreAll()
  })

  test('restart rejects a stateful ' + name + ' checkpoint without state PDC', async t => {
    const f = fixture(t)
    f.seed.fixtures[name].source.pdc = {}
    const read = fs.readFileSync
    t.mock.method(fs, 'readFileSync', (file, ...args) => file === f.harness.stateFile
      ? JSON.stringify(f.seed) : read(file, ...args))
    await assert.rejects(scenario(f.harness), /lacks its state PDC/)
    t.mock.restoreAll()
  })
}

test('restart rejects the obsolete sofa state_v1 key instead of its real deco_state_v1 key', async t => {
  const f = fixture(t)
  f.seed.fixtures.sofa.source.pdc = { 'kaleidoscopedecoplugin:state_v1': { bytes: 'c2F2ZWQ=' } }
  const read = fs.readFileSync
  t.mock.method(fs, 'readFileSync', (file, ...args) => file === f.harness.stateFile
    ? JSON.stringify(f.seed) : read(file, ...args))
  await assert.rejects(scenario(f.harness), /lacks its state PDC/)
  t.mock.restoreAll()
})

test('entrypoint clears old fixtures only in seed before the original full scenario', () => {
  const code = fs.readFileSync(path.join(__dirname, 'players.cjs'), 'utf8').replace(/\r\n/g, '\n')
  assert.equal([...code.matchAll(/inspect\(a,\s*'persistclear'\)/g)].length, 1)
  const guard = "if (phase === 'seed') await inspect(a, 'persistclear')"
  assert.ok(code.includes(guard), 'Cleanup must have an explicit seed-only guard')
  const restartBranch = /if \(phase === 'verify' \|\| phase === 'empty'\) \{[\s\S]*?\n    return\n  \}/.exec(code)
  assert.ok(restartBranch, 'Read-only restart phases must return before the original scenario')
  assert.ok(restartBranch.index + restartBranch[0].length < code.indexOf(guard))
  assert.ok(code.indexOf(guard) < code.indexOf("const supplied = await inspect(a,'supply')"))
})

test('seed cleanup validates all known fixture identities before deleting and never creates or clears inventory', () => {
  const code = fs.readFileSync(path.resolve(__dirname, '../../../src/acceptance/java/net/kaleidoscope/cookery/acceptance/ConnectedKitchenProbe.java'), 'utf8').replace(/\r\n/g, '\n')
  const cleanup = /private void clearPersistence\(\) \{([\s\S]*?)\n    private void preparePersistence\(\)/.exec(code)?.[1]
  assert.ok(cleanup)
  assert.match(cleanup, /Set\.of\(SOFA, DOLL, TUB, BOARD\)\.contains\(candidate\.id\(\)\)/)
  assert.match(cleanup, /throw new IllegalStateException\("Refusing to clear unknown persistent fixture/)
  assert.ok(cleanup.indexOf('previous.put(candidate.uuid(), candidate)') < cleanup.indexOf('furniture.destroy()'))
  assert.doesNotMatch(cleanup, /CraftEngineFurniture\.place|getInventory\(|setStorageContents|\.setItem\(/)
  const action = /case "persistclear" -> \{([\s\S]*?)\n                    \}/.exec(code)?.[1]
  assert.ok(action)
  assert.match(action, /!FIRST\.equals\(name\)/)
  assert.match(action, /clearPersistence\(\)/)
  assert.doesNotMatch(action, /preparePersistence|getInventory\(|CraftEngineFurniture\.place/)
})

function steeringFixture ({ onLook, blocked = false, timeoutError } = {}) {
  const controls = [], looks = [], timeouts = [], delays = []
  const bot = {
    entity: { position: { x: 0, y: 200, z: 0 } }, forward: true,
    setControlState: (control, enabled) => {
      assert.equal(control, 'forward')
      controls.push(enabled)
      bot.forward = enabled
    },
    lookAt: async (point, force) => {
      looks.push({ point: clone(point), force, forward: bot.forward })
      bot.target = point
      await onLook?.({ bot, point, force, index: looks.length, controls })
    }
  }
  let elapsed = 0
  const tick = ms => {
    elapsed += ms
    if (bot.forward && !blocked) {
      const dx = bot.target.x - bot.entity.position.x, dz = bot.target.z - bot.entity.position.z
      const distance = Math.hypot(dx, dz), step = Math.min(distance, ms * .002)
      if (distance > 0) {
        bot.entity.position.x += step * dx / distance
        bot.entity.position.z += step * dz / distance
      }
    }
  }
  const until = async (description, predicate, timeout) => {
    timeouts.push({ description, timeout })
    const end = elapsed + timeout
    do {
      if (await predicate()) return true
      tick(50)
    } while (elapsed < end)
    throw timeoutError ?? new Error('Timed out waiting for ' + description)
  }
  const helpers = loadMovementHelpers({ assert, until, delay: async ms => { delays.push(ms); tick(ms) },
    Vec3: class Vec3 { constructor (x, y, z) { Object.assign(this, { x, y, z }) } } })
  return { ...helpers, bot, controls, looks, timeouts, delays, target: { x: 2, y: 200, z: 0 } }
}

test('walking stops forward before every forced turn and keeps its original bound and settling delay', async () => {
  const f = steeringFixture()
  await f.walkTo(f.bot, f.target, 'bounded steering')
  assert.ok(f.looks.length > 1)
  assert.ok(f.looks.every(look => look.force === true && look.forward === false))
  assert.ok(f.looks.every(look => look.point.x === 2 && look.point.y === 201.62 && look.point.z === 0))
  assert.ok(Math.hypot(f.bot.entity.position.x - f.target.x, f.bot.entity.position.z - f.target.z) <= .35)
  assert.deepEqual(f.timeouts, [{ description: 'bounded steering', timeout: 5000 }])
  assert.deepEqual(f.delays, [200])
  assert.equal(f.bot.forward, false)
})

test('walking already within its arrival radius stops without turning or advancing', async () => {
  const f = steeringFixture()
  f.bot.entity.position.x = 1.8
  await f.walkTo(f.bot, f.target, 'already arrived')
  assert.deepEqual(f.looks, [])
  assert.deepEqual(f.controls, [false])
  assert.deepEqual(f.delays, [200])
})

test('walking rechecks arrival after a pending turn before allowing forward movement', async () => {
  const f = steeringFixture({ onLook: async ({ bot }) => { bot.entity.position.x = 2 } })
  await f.walkTo(f.bot, f.target, 'arrived during turn')
  assert.equal(f.looks.length, 1)
  assert.deepEqual(f.controls, [false, false])
  assert.equal(f.bot.entity.position.x, 2)
})

test('walking rechecks item disappearance after turning before resolving a stale target or advancing', async () => {
  let disappeared = false, targetReads = 0
  const f = steeringFixture({ onLook: async () => { disappeared = true } })
  await f.walkTo(f.bot, () => {
    targetReads++
    assert.equal(disappeared, false, 'A removed target must not be resolved again')
    return f.target
  }, 'drop removed during turn', .35, () => disappeared)
  assert.equal(targetReads, 1)
  assert.equal(f.looks.length, 1)
  assert.deepEqual(f.controls, [false, false])
  assert.deepEqual(f.bot.entity.position, { x: 0, y: 200, z: 0 })
})

test('walking honors an initial stop condition before resolving a missing target', async () => {
  const f = steeringFixture()
  await f.walkTo(f.bot, () => { throw new Error('Unexpected stale target read') }, 'drop already removed', .35, () => true)
  assert.deepEqual(f.looks, [])
  assert.deepEqual(f.controls, [false])
})

test('walking turns again if the live target mutates during rotation before resuming forward', async () => {
  const target = { x: 2, y: 200, z: 0 }
  const f = steeringFixture({ onLook: async ({ index, controls }) => {
    if (index === 1) target.x = 3
    if (index === 2) assert.ok(!controls.includes(true), 'The old heading must never be used after target movement')
  } })
  await f.walkTo(f.bot, () => target, 'moving target')
  assert.equal(f.looks[0].point.x, 2)
  assert.equal(f.looks[1].point.x, 3)
  assert.ok(f.controls.includes(true))
  assert.ok(Math.abs(f.bot.entity.position.x - 3) <= .35)
  assert.equal(f.bot.forward, false)
})

test('walking timeout preserves the original error and reports its last physical state after releasing controls', async () => {
  const failure = new Error('Timed out waiting for bounded steering')
  const originalStack = failure.stack
  const f = steeringFixture({ blocked: true, timeoutError: failure })
  await assert.rejects(f.walkTo(f.bot, f.target, 'bounded steering'), error => {
    assert.equal(error, failure)
    assert.deepEqual(error.walkTo, { description: 'bounded steering', stage: 'walking',
      position: { x: 0, y: 200, z: 0 }, target: f.target, distance: 2, stoppingDistance: .35 })
    assert.ok(error.stack.startsWith(originalStack))
    assert.match(error.stack, /walkTo=.*"distance":2/)
    assert.match(error.message, /"target":\{"x":2,"y":200,"z":0\}/)
    return true
  })
  assert.equal(f.timeouts[0].timeout, 5000)
  assert.equal(f.looks.length, 100, 'A blocked walk must stop at its original finite deadline')
  assert.equal(f.controls.at(-1), false)
  assert.equal(f.bot.forward, false)
  assert.deepEqual(f.delays, [])
})

test('walking look failure preserves the original error and stack and releases forward movement', async () => {
  const failure = new Error('Rotation failed'), originalStack = failure.stack
  const f = steeringFixture({ onLook: async () => { throw failure } })
  await assert.rejects(f.walkTo(f.bot, f.target, 'failed turn'), error => {
    assert.equal(error, failure)
    assert.equal(error.walkTo.stage, 'turning')
    assert.equal(error.walkTo.distance, 2)
    assert.ok(error.stack.startsWith(originalStack))
    assert.match(error.stack, /walkTo=/)
    return true
  })
  assert.deepEqual(f.controls, [false, false])
  assert.equal(f.bot.forward, false)
  assert.deepEqual(f.delays, [])
})

for (const kind of ['target resolver', 'stop condition']) {
  test('walking releases controls and retains a failing ' + kind, async () => {
    const failure = new Error(kind + ' failed'), f = steeringFixture()
    const target = kind === 'target resolver' ? () => { throw failure } : f.target
    const stop = kind === 'stop condition' ? () => { throw failure } : () => false
    await assert.rejects(f.walkTo(f.bot, target, 'failed input', .35, stop), error => {
      assert.equal(error, failure)
      assert.equal(error.walkTo.stage, 'checking')
      assert.deepEqual(error.walkTo.position, { x: 0, y: 200, z: 0 })
      return true
    })
    assert.deepEqual(f.controls, [false])
    assert.deepEqual(f.looks, [])
    assert.deepEqual(f.delays, [])
  })
}

for (const [axis, value] of [['x', NaN], ['y', Infinity], ['z', -Infinity]]) {
  test('walking refuses a nonfinite target ' + axis + ' before sending a turn or advancing', async () => {
    const f = steeringFixture()
    f.target[axis] = value
    await assert.rejects(f.walkTo(f.bot, f.target, 'invalid target'), /Missing or nonfinite walking target/)
    assert.deepEqual(f.looks, [])
    assert.deepEqual(f.controls, [false])
  })
  test('walking refuses a nonfinite player ' + axis + ' before sending a turn or advancing', async () => {
    const f = steeringFixture()
    f.bot.entity.position[axis] = value
    await assert.rejects(f.walkTo(f.bot, f.target, 'invalid player'), /Missing or nonfinite walking position/)
    assert.deepEqual(f.looks, [])
    assert.deepEqual(f.controls, [false])
  })
}

test('walking refuses a target that becomes nonfinite during rotation before advancing', async () => {
  const f = steeringFixture({ onLook: async () => { f.target.x = NaN } })
  await assert.rejects(f.walkTo(f.bot, () => f.target, 'invalidated target'), /Missing or nonfinite walking target/)
  assert.equal(f.looks.length, 1)
  assert.deepEqual(f.controls, [false, false])
  assert.equal(f.bot.forward, false)
})

for (const distance of [0, -.1, NaN, Infinity]) {
  test('walking refuses invalid stopping distance ' + distance + ' and still releases controls', async () => {
    const f = steeringFixture()
    await assert.rejects(f.walkTo(f.bot, f.target, 'invalid distance', distance), /Invalid walking stopping distance/)
    assert.deepEqual(f.looks, [])
    assert.deepEqual(f.timeouts, [])
    assert.deepEqual(f.controls, [false])
  })
}

function movementFixture ({ id = IDS.sofa, blocked = false, badPdc = false, alreadyCarried = false, drifting = false,
  networkChangeDuringWalk } = {}) {
  const pdc = { 'state:fixture': { bytes: 'c2F2ZWQ=' } }
  const removed = { inventory: Array.from({ length: 36 }, () => ({})), drops: [{ id, count: 1, pdc }] }
  const inventory = clone(removed.inventory), peerInventory = clone(removed.inventory), controls = [], looks = [], timeouts = [], reads = []
  const peer = { username: 'B' }
  const start = { x: 681.7914987961115, y: 200, z: 9.5 }
  const origin = { x: 682.5, y: 200.45, z: 9.5 }
  const entity = {
    id: 100, name: 'item', position: { x: 683.3502644542098, y: 200, z: 8.691446718075056 },
    metadata: { 8: { itemCount: 1 } }, getDroppedItem: () => ({ count: 1, components: [{
      type: 'custom_data', data: { type: 'compound', value: { 'craftengine:id': { type: 'string', value: id } } }
    }] })
  }
  const bot = {
    username: 'A',
    entity: { position: clone(start) }, entities: { 100: entity }, supportFeature: key => {
      assert.equal(key, 'metadataIxOfItem')
      return 8
    },
    lookAt: async target => { bot.target = target; looks.push(clone(target)) },
    setControlState: (control, enabled) => {
      assert.equal(control, 'forward')
      bot.forward = enabled
      controls.push(enabled)
    }
  }
  let elapsed = 0, walkingMs = 0, networkChanged = false
  const reward = () => ({ id, count: 1, pdc: badPdc ? { 'state:fixture': { bytes: 'Y2hhbmdlZA==' } } : clone(pdc) })
  if (alreadyCarried) { inventory[4] = reward(); bot.entities = {} }
  const tick = async ms => {
    elapsed += ms
    if (bot.forward && !blocked && !networkChanged) {
      walkingMs += ms
      const dx = bot.target.x - bot.entity.position.x, dz = bot.target.z - bot.entity.position.z
      const distance = Math.hypot(dx, dz), step = Math.min(distance, ms * .002)
      bot.entity.position.x += step * dx / distance
      bot.entity.position.z += step * dz / distance
      if (drifting && elapsed <= 300) entity.position.z -= .05
      if (networkChangeDuringWalk && walkingMs >= 100) {
        networkChanged = true
        if (networkChangeDuringWalk === 'peer') peerInventory[4] = reward()
        if (networkChangeDuringWalk === 'replaced') bot.entities[100] = { ...entity }
        else delete bot.entities[100]
      }
      if (!networkChanged && bot.entities[100] && Math.hypot(bot.entity.position.x - entity.position.x, bot.entity.position.z - entity.position.z) <= .55) {
        inventory[4] = reward()
        delete bot.entities[100]
      }
    }
  }
  const until = async (description, predicate, timeout = 15000) => {
    timeouts.push({ description, timeout })
    const end = elapsed + timeout
    do {
      const result = await predicate()
      if (result) return result
      await tick(50)
    } while (elapsed < end)
    throw new Error('Timed out waiting for ' + description)
  }
  const bindings = {
    assert, until, delay: tick, inspect: async participant => {
      reads.push(participant.username)
      return { inventory: clone(participant === peer ? peerInventory : inventory), drops: clone(removed.drops) }
    },
    count: (items, wanted) => items.filter(item => item.id === wanted).reduce((sum, item) => sum + item.count, 0),
    Vec3: class Vec3 { constructor (x, y, z) { Object.assign(this, { x, y, z }) } }
  }
  const helpers = loadMovementHelpers(bindings)
  return { ...helpers, bot, peer, id, removed, origin, start, inventory, peerInventory, entity, controls, looks, timeouts, reads, walkingMs: () => walkingMs }
}

test('furniture pickup walks to the actual diagonal Paper5 drop and back using position feedback', async () => {
  const f = movementFixture()
  const recovered = await f.pickUpFurniture(f.bot, f.id, f.removed, f.origin, 'styled sofa pickup')
  assert.equal(recovered.slot, 4)
  assert.deepEqual(recovered.snapshot.inventory[4].pdc, f.removed.drops[0].pdc)
  assert.ok(f.walkingMs() > 300, 'The saved Paper5 distance cannot be covered by the old timed step')
  assert.equal(f.bot.entities[100], undefined, 'The real network drop must have been collected')
  assert.ok(Math.hypot(f.bot.entity.position.x - f.entity.position.x, f.bot.entity.position.z - f.entity.position.z) <= .55)
  await f.walkTo(f.bot, f.start, 'return for styled sofa replacement')
  assert.ok(Math.hypot(f.bot.entity.position.x - f.start.x, f.bot.entity.position.z - f.start.z) <= .35)
  assert.equal(f.bot.forward, false)
  assert.ok(f.timeouts.filter(value => value.description.startsWith('walk') || value.description.startsWith('return')).every(value => value.timeout === 5000))
})

test('posed doll pickup follows a drifting network drop and preserves its PDC', async () => {
  const f = movementFixture({ id: IDS.doll, drifting: true })
  const recovered = await f.pickUpFurniture(f.bot, f.id, f.removed, f.origin, 'posed doll pickup')
  assert.equal(recovered.snapshot.inventory[4].id, IDS.doll)
  assert.deepEqual(recovered.snapshot.inventory[4].pdc, f.removed.drops[0].pdc)
  assert.ok(new Set(f.looks.map(position => position.z)).size > 1, 'Movement must update its target from live entity positions')
  assert.equal(f.bot.forward, false)
})

test('blocked pickup stops after its bounded walk and releases forward movement', async () => {
  const f = movementFixture({ blocked: true })
  await assert.rejects(f.pickUpFurniture(f.bot, f.id, f.removed, f.origin, 'styled sofa pickup'), /Timed out waiting for walk to/)
  assert.deepEqual(f.bot.entity.position, f.start)
  assert.equal(f.bot.forward, false)
  assert.deepEqual(f.inventory, f.removed.inventory, 'A failed physical walk must not fabricate a pickup')
  assert.equal(f.timeouts.find(value => value.description.startsWith('walk')).timeout, 5000)
})

test('furniture pickup rejects lost PDC after a real simulated approach', async () => {
  const f = movementFixture({ badPdc: true })
  await assert.rejects(f.pickUpFurniture(f.bot, f.id, f.removed, f.origin, 'styled sofa pickup'), /lost its state PDC/)
  assert.ok(f.walkingMs() > 0)
  assert.equal(f.bot.forward, false)
})

test('furniture pickup rejects duplicate matching network drops before walking', async () => {
  const f = movementFixture()
  f.bot.entities[101] = { ...f.entity }
  await assert.rejects(f.pickUpFurniture(f.bot, f.id, f.removed, f.origin, 'styled sofa pickup'), /multiple nearby network drops/)
  assert.deepEqual(f.controls, [])
})

test('furniture pickup accepts an already collected unique item without requiring a stale entity', async () => {
  const f = movementFixture({ alreadyCarried: true })
  const recovered = await f.pickUpFurniture(f.bot, f.id, f.removed, f.origin, 'styled sofa pickup')
  assert.equal(recovered.slot, 4)
  assert.deepEqual(f.controls, [])
})

test('furniture pickup stops following a removed drop when the peer collects it during the walk', async () => {
  const f = movementFixture({ networkChangeDuringWalk: 'peer' })
  const recovered = await f.pickUpFurniture(f.bot, f.id, f.removed, f.origin, 'styled sofa pickup', undefined, [f.bot, f.peer])
  assert.equal(recovered.owner, 'B')
  assert.equal(recovered.slot, 4)
  assert.equal(recovered.snapshot.inventory[4].count, 1)
  assert.deepEqual(recovered.snapshot.inventory[4].pdc, f.removed.drops[0].pdc)
  assert.equal(f.walkingMs(), 100, 'The walker must stop on removal rather than chase an unreachable stale position')
  assert.equal(f.bot.forward, false)
  assert.equal(f.controls.at(-1), false)
  assert.deepEqual(f.reads, ['A', 'B', 'A', 'B'], 'Walking must use network state rather than server inventory reads each frame')
})

test('a drop disappearing during the walk cannot pass pickup without a participant inventory item', async () => {
  const f = movementFixture({ networkChangeDuringWalk: 'gone' })
  await assert.rejects(f.pickUpFurniture(f.bot, f.id, f.removed, f.origin, 'styled sofa pickup', undefined, [f.bot, f.peer]),
    error => error.message === 'Timed out waiting for styled sofa pickup')
  assert.equal(f.walkingMs(), 100)
  assert.equal(f.bot.forward, false)
  assert.equal(f.controls.at(-1), false)
  assert.deepEqual(f.inventory, f.removed.inventory)
  assert.deepEqual(f.peerInventory, f.removed.inventory)
})

test('a reused network entity ID stops chasing the old drop but cannot prove an inventory pickup', async () => {
  const f = movementFixture({ networkChangeDuringWalk: 'replaced' })
  await assert.rejects(f.pickUpFurniture(f.bot, f.id, f.removed, f.origin, 'styled sofa pickup'),
    error => error.message === 'Timed out waiting for styled sofa pickup')
  assert.notEqual(f.bot.entities[100], f.entity)
  assert.equal(f.walkingMs(), 100)
  assert.equal(f.bot.forward, false)
})

test('a peer collecting the drop during the walk must still retain its exact state PDC', async () => {
  const f = movementFixture({ networkChangeDuringWalk: 'peer', badPdc: true })
  await assert.rejects(f.pickUpFurniture(f.bot, f.id, f.removed, f.origin, 'styled sofa pickup', undefined, [f.bot, f.peer]),
    /lost its state PDC/)
  assert.equal(f.walkingMs(), 100)
  assert.equal(f.bot.forward, false)
})

test('legacy peer retreat and return use bounded walking on the fixture platform around furniture replay', async () => {
  const f = movementFixture({ alreadyCarried: true })
  const original = clone(f.bot.entity.position)
  await f.parkFurniturePeer(f.bot)
  assert.ok(Math.hypot(f.bot.entity.position.x - 682.5, f.bot.entity.position.z - 9.5) > 5.5)
  assert.ok(f.bot.entity.position.x >= 676 && f.bot.entity.position.x < 685)
  assert.ok(f.bot.entity.position.z >= 5 && f.bot.entity.position.z < 12)
  await f.returnFurniturePeer(f.bot, original)
  assert.ok(Math.hypot(f.bot.entity.position.x - original.x, f.bot.entity.position.z - original.z) <= .2)
  assert.equal(f.bot.forward, false)
  const code = fs.readFileSync(path.join(__dirname, 'players.cjs'), 'utf8')
  const sofa = code.slice(code.indexOf('async function sofaStyle ('), code.indexOf('async function dollPose ('))
  assert.ok(sofa.indexOf('await parkFurniturePeer(b)') > sofa.indexOf("assert.equal(left.sofa, style('red', 'left'))"))
  assert.ok(sofa.indexOf('await parkFurniturePeer(b)') < sofa.indexOf('a.attack(target)'))
  assert.doesNotMatch(sofa, /b\.setControlState\('back'/)
  assert.match(sofa, /assert\.equal\(sofaItem\.owner, a\.username\)/)
  assert.match(code, /try \{ await dollPose\(a\) \} finally \{ await inspect\(a, 'cleardoll'\) \}\s*\} finally \{ await returnFurniturePeer\(b, peerPosition\) \}/)
})

function readinessFixture (snapshots) {
  let reads = 0, actions = 0
  const read = async () => clone(snapshots[Math.min(reads++, snapshots.length - 1)])
  const until = async (description, predicate) => {
    for (let attempt = 0; attempt < 8; attempt++) {
      const result = await predicate()
      if (result) return result
    }
    throw new Error('Timed out waiting for ' + description)
  }
  const helpers = loadMovementHelpers({ assert, until, inspect: read })
  return { ...helpers, read, action: () => { actions++ }, actions: () => actions, reads: () => reads }
}

test('a single furniture action waits through stale and receipt-only snapshots before the target state', async () => {
  const old = { furnitureInteractions: 4, board: 'empty', tulips: 2 }
  const f = readinessFixture([old, old, { ...old, furnitureInteractions: 5 },
    { furnitureInteractions: 5, board: 'decorated', tulips: 1 }])
  const result = await f.useAndObserve({}, f.action, 'decoration committed', value => value.board === 'decorated' && value.tulips === 1)
  assert.equal(result.board, 'decorated')
  assert.equal(f.actions(), 1, 'Snapshot polling must never resend the interaction')
  assert.equal(f.reads(), 4)
})

test('a single furniture action that never reaches its target fails without retrying the click', async () => {
  const f = readinessFixture([{ furnitureInteractions: 4, board: 'empty' }, { furnitureInteractions: 5, board: 'empty' }])
  await assert.rejects(f.useAndObserve({}, f.action, 'decoration committed', value => value.board === 'decorated'), /Timed out/)
  assert.equal(f.actions(), 1)
})

test('an unchanged no-op snapshot cannot pass until the server receives its single interaction', async () => {
  const f = readinessFixture([{ furnitureInteractions: 7, hasContents: false, berries: 1 }])
  await assert.rejects(f.useAndObserve({}, f.action, 'repeated empty tub', value => !value.hasContents && value.berries === 1), /Timed out/)
  assert.equal(f.actions(), 1)
})

test('a no-op passes only after its receipt while preserving the exact returned ingredient count', async () => {
  const old = { furnitureInteractions: 7, hasContents: false, berries: 1 }
  const f = readinessFixture([old, old, { ...old, furnitureInteractions: 8 }])
  const result = await f.useAndObserve({}, f.action, 'repeated empty tub', value => !value.hasContents,
    f.read, value => { assert.equal(value.berries, 1) })
  assert.equal(result.furnitureInteractions, 8)
  assert.equal(f.actions(), 1)
})

test('a cancellation counter arriving before the receipt cannot hide a later illegal state change', async () => {
  const before = { furnitureInteractions: 2, canceledTrades: 0, dye: 3, style: 'none' }
  const f = readinessFixture([before, { ...before, canceledTrades: 1 },
    { furnitureInteractions: 3, canceledTrades: 1, dye: 2, style: 'red' }])
  await assert.rejects(f.useAndObserve({}, f.action, 'canceled trade', value => value.canceledTrades === 1,
    f.read, value => { assert.equal(value.dye, 3); assert.equal(value.style, 'none') }), { code: 'ERR_ASSERTION' })
  assert.equal(f.actions(), 1)
  assert.equal(f.reads(), 3)
})

test('furniture readiness rejects a missing receipt counter before sending any action', async () => {
  const f = readinessFixture([{ hasContents: false }])
  await assert.rejects(f.useAndObserve({}, f.action, 'empty tub', () => true), /Missing furniture interaction receipt counter/)
  assert.equal(f.actions(), 0)
})

function concurrentTulipFixture ({ receipts = ['A'], replaceHitbox = true, drops, changedInventory } = {}) {
  const a = { username: 'A' }, b = { username: 'B' }, actions = [], events = [], postReads = { A: 0, B: 0 }
  const inventory = [{ id: 'minecraft:shears', count: 1 }]
  const returned = drops ?? [{ id: 'minecraft:pink_tulip', count: 1 }]
  let emptyReceipt = false
  const use = (bot, target) => {
    assert.equal(actions.length < 2, true, 'The concurrent old-target actions must never be retried')
    assert.deepEqual(events.slice(0, 2), ['read:A', 'read:B'], 'Both baselines must be captured before either action')
    assert.equal(events.length, 2 + actions.length, 'Both actions must be sent before post-action polling')
    assert.equal(target, 123)
    actions.push({ player: bot.username, target }); events.push('use:' + bot.username)
  }
  const read = async bot => {
    events.push('read:' + bot.username)
    const before = { furnitureInteractions: bot === a ? 2 : 1, board: 'decorated', boardHitbox: 123,
      inventory: clone(inventory), drops: [] }
    if (!actions.length) return before
    assert.equal(actions.length, 2)
    if (++postReads[bot.username] === 1) return before
    return { ...before, furnitureInteractions: before.furnitureInteractions + Number(receipts.includes(bot.username)) + Number(bot === b && emptyReceipt),
      board: 'empty', boardHitbox: replaceHitbox ? 127 : 123,
      inventory: changedInventory === bot.username ? [{ id: 'minecraft:shears', count: 2 }] : clone(inventory), drops: clone(returned) }
  }
  const until = async (description, predicate) => {
    for (let attempt = 0; attempt < 8; attempt++) {
      const result = await predicate()
      if (result) return result
    }
    throw new Error('Timed out waiting for ' + description)
  }
  const helpers = loadMovementHelpers({ assert, until, inspect: read })
  return { ...helpers, a, b, use, read, actions, events,
    harvest: () => helpers.harvestTulipConcurrently(a, b, 123, use, 'empty', read),
    receiveEmpty: (bot, target) => {
      assert.equal(bot, b)
      assert.equal(target, 127, 'The later empty request must target the replacement hitbox')
      emptyReceipt = true
    } }
}

test('concurrent tulip harvest accepts an expired second target only with a replacement hitbox and a real first receipt', async () => {
  const f = concurrentTulipFixture()
  const result = await f.harvest()
  assert.deepEqual(result.attemptedPlayers, ['A', 'B'])
  assert.deepEqual(result.acknowledged, ['A'])
  assert.equal(result.oldHitbox, 123); assert.equal(result.newHitbox, 127)
  assert.deepEqual(f.actions, [{ player: 'A', target: 123 }, { player: 'B', target: 123 }])
  let emptyActions = 0
  const repeated = await f.useAndObserve(f.b, () => { emptyActions++; f.receiveEmpty(f.b, result.newHitbox) }, 'empty replacement board received',
    value => value.board === 'empty', f.read, value => { assert.deepEqual(value.drops, result.harvested.drops) })
  assert.equal(emptyActions, 1)
  assert.equal(repeated.furnitureInteractions, 2, 'The later empty request must receive its own B acknowledgement')
  assert.deepEqual(repeated.drops, [{ id: 'minecraft:pink_tulip', count: 1 }])
})

test('concurrent tulip harvest accepts two real receipts without claiming a hitbox replacement', async () => {
  const f = concurrentTulipFixture({ receipts: ['A', 'B'], replaceHitbox: false })
  const result = await f.harvest()
  assert.deepEqual(result.acknowledged, ['A', 'B'])
  assert.equal(result.newHitbox, result.oldHitbox)
  assert.equal(f.actions.length, 2)
})

test('concurrent tulip harvest rejects an empty board and a replaced hitbox when neither request has a receipt', async () => {
  const f = concurrentTulipFixture({ receipts: [] })
  await assert.rejects(f.harvest(), /Timed out waiting for single concurrent tulip harvest committed/)
  assert.equal(f.actions.length, 2)
})

test('concurrent tulip harvest rejects a missing second receipt when the hitbox was not replaced', async () => {
  const f = concurrentTulipFixture({ replaceHitbox: false })
  await assert.rejects(f.harvest(), /Timed out waiting for single concurrent tulip harvest committed/)
  assert.equal(f.actions.length, 2)
})

for (const drops of [
  [{ id: 'minecraft:pink_tulip', count: 2 }],
  [{ id: 'minecraft:pink_tulip', count: 1 }, { id: 'minecraft:red_tulip', count: 1 }]
]) {
  test('concurrent tulip harvest rejects duplicate drops with ' + drops.length + ' logged stacks', async () => {
    const f = concurrentTulipFixture({ drops })
    await assert.rejects(f.harvest(), { code: 'ERR_ASSERTION' })
    assert.equal(f.actions.length, 2)
  })
}

test('concurrent tulip harvest checks the second player inventory on every observation', async () => {
  const f = concurrentTulipFixture({ changedInventory: 'B' })
  await assert.rejects(f.harvest(), /Board cleanup changed an unrelated inventory item/)
  assert.equal(f.actions.length, 2)
})

function boardCleanupFixture ({ color = 'pink', alreadyCollected, pickupBy = 'A', missingPickup = false,
  refusedMove = false, duplicatePickup = false, changedComponent = false, occupiedOffhand = false,
  inFlightMove = false, badCursor } = {}) {
  const players = ['A', 'B'].map(username => ({ username }))
  const [a, b] = players, actions = [], controls = [], reads = [], cursorStates = []
  let movePending = false, moveReads = 0
  const inventories = players.map((bot, index) => Array.from({ length: 36 }, (_, slot) => slot === 0
    ? { id: 'minecraft:red_tulip', count: index + 1 }
    : slot === 1 ? { id: 'minecraft:shears', count: 1 } : { id: 'minecraft:cobblestone', count: 64 }))
  const finished = { board: 'empty-board', boardHitbox: 127, drops: [{ id: 'minecraft:' + color + '_tulip', count: 1 }] }
  const baselines = inventories.map(inventory => ({ inventory: clone(inventory), top: [{}, {}, {}, {}, {}], cursor: {} }))
  const entity = { id: 100, name: 'item', position: { x: 682.5, y: 200, z: 9.9 }, metadata: { 8: { itemCount: 1 } },
    getDroppedItem: () => ({ name: color + '_tulip', count: 1 }) }
  const start = { x: 680.5, y: 200, z: 9.5 }
  for (const bot of players) {
    bot.entity = { position: clone(start) }; bot.entities = { 100: entity }
    bot.supportFeature = key => { assert.equal(key, 'metadataIxOfItem'); return 8 }
    bot.lookAt = async target => { bot.target = target }
    bot.setControlState = (control, enabled) => {
      assert.equal(control, 'forward'); controls.push(enabled); bot.forward = enabled
      if (!enabled) return
      actions.push('walk')
      bot.entity.position.x = bot.target.x; bot.entity.position.z = bot.target.z
      if (bot.entities[100]) {
        for (const player of players) delete player.entities[100]
        if (!missingPickup) {
          const index = pickupBy === 'A' ? 0 : 1, inventory = inventories[index]
          const slot = inventory.findIndex(item => item.id === finished.drops[0].id || !item.id)
          assert.ok(slot >= 0, 'The simulated recipient must have real room for the flower')
          if (inventory[slot].id) inventory[slot].count += duplicatePickup ? 2 : 1
          else inventory[slot] = { ...finished.drops[0], count: duplicatePickup ? 2 : 1 }
        }
      }
    }
  }
  a.inventory = { hotbarStart: 36, slots: { 38: { name: 'cobblestone', count: 64 },
    45: occupiedOffhand ? { name: 'stone', count: 1 } : null } }
  a.getEquipmentDestSlot = destination => { assert.equal(destination, 'off-hand'); return 45 }
  a.moveSlotItem = async (source, destination) => {
    assert.equal(source, 38); assert.equal(destination, 45)
    assert.equal(a.inventory.slots[destination], null, 'An occupied offhand must never be overwritten')
    actions.push('move-stack')
    if (!refusedMove) {
      a.inventory.slots[destination] = a.inventory.slots[source]; a.inventory.slots[source] = null
      inventories[0][2] = {}
      movePending = inFlightMove
    }
  }
  if (alreadyCollected) {
    assert.equal(color, 'red')
    inventories[alreadyCollected === 'A' ? 0 : 1][0].count++
    for (const player of players) delete player.entities[100]
  }
  if (changedComponent) inventories[0][1].pdc = { 'unexpected:state': 'changed' }
  const read = async bot => {
    reads.push(bot.username)
    const index = players.indexOf(bot)
    let cursor = {}
    if (bot === a && movePending) {
      if (++moveReads === 1) cursor = { id: badCursor === 'type' ? 'minecraft:stone' : 'minecraft:cobblestone',
        count: badCursor === 'count' ? 63 : 64 }
      else movePending = false
      cursorStates.push(clone(cursor))
    }
    return { ...clone(finished), inventory: clone(inventories[index]), top: [{}, {}, {}, {}, {}], cursor }
  }
  const until = async (description, predicate) => {
    for (let attempt = 0; attempt < 8; attempt++) {
      const result = await predicate()
      if (result) return result
    }
    throw new Error('Timed out waiting for ' + description)
  }
  const Vec3 = class Vec3 { constructor (x, y, z) { Object.assign(this, { x, y, z }) } }
  const helpers = loadMovementHelpers({ assert, until, delay: async () => {}, inspect: read, Vec3 })
  return { ...helpers, a, b, players, inventories, baselines, finished, actions, controls, reads, cursorStates, start,
    cleanup: () => helpers.collectBoardTulip(a, b, baselines, finished, new Vec3(682.5, 200.45, 9.5), read) }
}

test('full-storage board cleanup reserves one offhand stack and proves the unique flower before the next setup', async () => {
  const f = boardCleanupFixture()
  const collected = await f.cleanup()
  assert.deepEqual(collected, { owner: 'A', slot: 2, id: 'minecraft:pink_tulip', count: 1, movedStackToOffhand: true })
  assert.deepEqual(f.a.inventory.slots[45], { name: 'cobblestone', count: 64 })
  assert.deepEqual(f.a.entity.position, f.start)
  assert.equal(f.a.forward, false)
  assert.deepEqual(f.actions, ['move-stack', 'walk', 'walk'])
  const code = fs.readFileSync(path.join(__dirname, 'players.cjs'), 'utf8')
  const board = code.slice(code.indexOf('async function tulipBoard ('), code.indexOf('async function sofaStyle ('))
  assert.ok(board.indexOf('await collectBoardTulip(') > board.indexOf('assert.deepEqual(repeated.drops, harvested.drops)'))
  assert.doesNotMatch(board, /inspect\(.*'sofa'/)
  assert.match(code, /try \{ await tulipBoard\(a,b\) \} finally \{ await inspect\(a, 'clearboard'\) \}/)
})

test('a naturally merged red flower is accounted for without moving a stack or resetting the fixture first', async () => {
  const f = boardCleanupFixture({ color: 'red', alreadyCollected: 'A' })
  assert.deepEqual(await f.cleanup(), { owner: 'A', slot: 0, id: 'minecraft:red_tulip', count: 1, movedStackToOffhand: false })
  assert.deepEqual(f.actions, [])
  assert.equal(f.inventories[0][0].count, f.baselines[0].inventory[0].count + 1)
})

test('board cleanup accepts the peer naturally merging the sole red flower during the collection walk', async () => {
  const f = boardCleanupFixture({ color: 'red', pickupBy: 'B' })
  const collected = await f.cleanup()
  assert.equal(collected.owner, 'B'); assert.equal(collected.slot, 0)
  assert.equal(f.inventories[1][0].count, f.baselines[1].inventory[0].count + 1)
  assert.equal(f.a.forward, false)
  assert.deepEqual(f.a.entity.position, f.start)
})

test('board cleanup rejects a vanished flower when authoritative inventories do not contain its gain', async () => {
  const f = boardCleanupFixture({ missingPickup: true })
  await assert.rejects(f.cleanup(), /Timed out waiting for recorded board flower in server inventory/)
  assert.equal(f.a.forward, false)
  assert.equal(f.controls.at(-1), false)
})

test('board cleanup rejects two returned flowers instead of allowing them into the next fixture', async () => {
  const f = boardCleanupFixture({ duplicatePickup: true })
  await assert.rejects(f.cleanup(), /only its one recorded flower/)
  assert.equal(f.a.forward, false)
})

test('board cleanup keeps exact component checks on unrelated inventory items', async () => {
  const f = boardCleanupFixture({ changedComponent: true })
  await assert.rejects(f.cleanup(), /changed an unrelated inventory item/)
  assert.deepEqual(f.actions, [])
})

test('a refused offhand reservation fails before any pickup walk and never retries the inventory action', async () => {
  const f = boardCleanupFixture({ refusedMove: true })
  await assert.rejects(f.cleanup(), /Timed out waiting for board cleanup storage slot reserved/)
  assert.deepEqual(f.actions, ['move-stack'])
  assert.deepEqual(f.inventories[0], f.baselines[0].inventory)
})

test('offhand reservation waits through the exact in-flight cursor stack before starting collection', async () => {
  const f = boardCleanupFixture({ inFlightMove: true })
  const collected = await f.cleanup()
  assert.equal(collected.count, 1)
  assert.deepEqual(f.cursorStates, [{ id: 'minecraft:cobblestone', count: 64 }, {}])
  assert.deepEqual(f.actions, ['move-stack', 'walk', 'walk'])
  assert.equal(f.a.forward, false)
})

for (const badCursor of ['type', 'count']) {
  test('offhand reservation rejects an in-flight cursor with changed ' + badCursor, async () => {
    const f = boardCleanupFixture({ inFlightMove: true, badCursor })
    await assert.rejects(f.cleanup(), /cursor must contain exactly the stack being moved/)
    assert.deepEqual(f.actions, ['move-stack'])
    assert.deepEqual(f.controls, [])
  })
}

test('board cleanup never overwrites an occupied offhand to make inventory space', async () => {
  const f = boardCleanupFixture({ occupiedOffhand: true })
  await assert.rejects(f.cleanup(), /Timed out waiting for board cleanup inventory source ready/)
  assert.deepEqual(f.actions, [])
  assert.deepEqual(f.a.inventory.slots[45], { name: 'stone', count: 1 })
})

test('a flower inventory gain is rejected before any unique flower drop has been recorded', () => {
  const f = boardCleanupFixture({ color: 'red', alreadyCollected: 'A' })
  assert.throws(() => f.boardTulipGain(f.players, f.inventories.map(inventory => ({ inventory })),
    f.baselines.map(value => value.inventory)), /changed an unrelated inventory item/)
})

test('one recorded board flower cannot justify a gain in both players inventories', () => {
  const f = boardCleanupFixture({ color: 'red', alreadyCollected: 'A' })
  f.inventories[1][0].count++
  assert.throws(() => f.boardTulipGain(f.players, f.inventories.map(inventory => ({ inventory })),
    f.baselines.map(value => value.inventory), f.finished.drops[0]), /duplicated the returned flower across slots or players/)
})

test('one recorded board flower cannot justify gains in two slots of the same player', () => {
  const f = boardCleanupFixture()
  const base = f.baselines.map(value => clone(value.inventory))
  base[0][2] = {}; base[0][3] = {}
  const actual = clone(base)
  actual[0][2] = clone(f.finished.drops[0]); actual[0][3] = clone(f.finished.drops[0])
  assert.throws(() => f.boardTulipGain(f.players, actual.map(inventory => ({ inventory })), base, f.finished.drops[0]),
    /duplicated the returned flower across slots or players/)
})

test('a flower collected into a newly empty slot must retain its exact recorded item data', () => {
  const f = boardCleanupFixture()
  const base = f.baselines.map(value => clone(value.inventory))
  base[0][2] = {}
  const actual = clone(base)
  actual[0][2] = { ...f.finished.drops[0], pdc: { 'unexpected:state': 'changed' } }
  assert.throws(() => f.boardTulipGain(f.players, actual.map(inventory => ({ inventory })), base, f.finished.drops[0]),
    /changed the recorded flower item data/)
})

function dollReadinessFixture ({ commit = true, refresh = true } = {}) {
  const pose = [1, 1, 1, 0, 0, 0, 0, 0, 0], adjusted = [1.1, 1.1, 1.1, .05, .05, .05, 15, 15, 15]
  const bot = { currentWindow: { id: 1, inventoryStart: 36 } }, clicks = [], reads = []
  let pending, refreshWindow
  const until = async (description, predicate) => {
    for (let attempt = 0; attempt < 8; attempt++) {
      const result = await predicate()
      if (result) return result
      if (refreshWindow && refresh) { bot.currentWindow = refreshWindow; refreshWindow = undefined }
    }
    throw new Error('Timed out waiting for ' + description)
  }
  const click = async (actor, slot) => {
    assert.equal(actor, bot)
    assert.equal(slot, 18 + clicks.length)
    assert.equal(bot.currentWindow.id, 1 + clicks.length, 'The next axis must use the refreshed menu window')
    clicks.push({ slot, window: bot.currentWindow.id })
    pending = { axis: slot - 18, observations: 0 }
  }
  const read = async () => {
    reads.push(pose.join(','))
    if (pending && ++pending.observations >= 2 && commit) {
      pose[pending.axis] = adjusted[pending.axis]
      refreshWindow = { id: bot.currentWindow.id + 1, inventoryStart: 36 }
      pending = undefined
    }
    return { dollPose: pose.join(',') }
  }
  const helpers = loadMovementHelpers({ assert, until, click, inspect: read })
  return { ...helpers, bot, click, read, clicks, reads, pose }
}

test('each doll axis waits for its committed pose and a new menu before the next single click', async () => {
  const f = dollReadinessFixture()
  await f.adjustDollAxes(f.bot, value => value.dollPose, f.read)
  assert.deepEqual(f.pose, [1.1, 1.1, 1.1, .05, .05, .05, 15, 15, 15])
  assert.deepEqual(f.clicks.map(value => value.slot), Array.from({ length: 9 }, (_, axis) => 18 + axis))
  assert.equal(f.bot.currentWindow.id, 10)
  assert.equal(f.reads.length, 18, 'Each axis must first observe the previous pose, then its committed pose')
})

test('a doll axis that never commits fails after exactly one click', async () => {
  const f = dollReadinessFixture({ commit: false })
  await assert.rejects(f.adjustDollAxes(f.bot, value => value.dollPose, f.read), /Timed out waiting for doll axis 0 committed/)
  assert.equal(f.clicks.length, 1)
})

test('a committed doll pose without a refreshed menu cannot send another axis click', async () => {
  const f = dollReadinessFixture({ refresh: false })
  await assert.rejects(f.adjustDollAxes(f.bot, value => value.dollPose, f.read), /Timed out waiting for refreshed doll menu after axis 0/)
  assert.equal(f.clicks.length, 1)
})
