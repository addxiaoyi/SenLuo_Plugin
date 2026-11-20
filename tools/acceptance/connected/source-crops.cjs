'use strict'

// Native protocol actions drive crops. Probe commands only set fixtures, observe,
// inject one declared fault, or seal a checkpoint; never call a crop behavior.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { createHash } = require('node:crypto')

const NS = 'kaleidoscopecookery:'
const PLAYERS = ['SenLuoProbeA', 'SenLuoProbeB']
const CROPS = ['tomato', 'chili', 'lettuce']
const POSITIONS = { tomato: [724, 200, 4], chili: [728, 200, 4], lettuce: [732, 200, 4], wheat: [728, 200, 10],
  persistTomato: [724, 200, 7], persistChili: [728, 200, 7], persistLettuce: [732, 200, 7] }
const PERSISTED = Object.keys(POSITIONS).filter(name => name !== 'wheat')
const SHA = /^[a-f0-9]{64}$/
const hash = value => createHash('sha256').update(value).digest('hex')
const stable = value => JSON.stringify(value && typeof value === 'object'
  ? Array.isArray(value) ? value.map(v => JSON.parse(stable(v)))
    : Object.fromEntries(Object.keys(value).sort().map(k => [k, JSON.parse(stable(value[k]))])) : value)
const cropOf = key => key.startsWith('persist') ? key.slice(7).toLowerCase() : key
const at = (a, b) => Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((v, i) => v === b[i])

function bytes (value, label) {
  assert.equal(typeof value, 'string', label)
  assert.ok(value.length > 0, label)
  const decoded = Buffer.from(value, 'base64')
  assert.equal(decoded.toString('base64'), value, 'Noncanonical ' + label)
  return decoded
}
function itemKey (item, undamaged = false) {
  assert.ok(item && typeof item === 'object' && !Array.isArray(item), 'Missing complete item evidence')
  if (!Object.keys(item).length) return null
  assert.ok(Number.isSafeInteger(item.count) && item.count > 0, 'Invalid item count')
  assert.equal(typeof item.id, 'string'); assert.ok(item.id.includes(':'))
  assert.equal(typeof item.material, 'string')
  assert.ok(item.pdc && typeof item.pdc === 'object')
  bytes(item.serialized, 'serialized item')
  const unit = bytes(item.unitSerialized, 'unitSerialized')
  assert.ok(SHA.test(item.unitSha256) && hash(unit) === item.unitSha256, 'Unit component checksum differs')
  assert.ok(Number.isSafeInteger(item.damage) && item.damage >= 0, 'Missing item damage')
  assert.ok(item.enchantments && typeof item.enchantments === 'object', 'Missing enchantments')
  const value = { id: item.id, material: item.material, pdc: item.pdc, enchantments: item.enchantments,
    damage: undamaged ? 0 : item.damage, unitSerialized: item.unitSerialized }
  if (undamaged) value.unitSerialized = bytes(item.undamagedUnitSerialized, 'undamagedUnitSerialized').toString('base64')
  return stable(value)
}
function allItems (snapshot) {
  assert.deepEqual(Object.keys(snapshot.players).sort(), [...PLAYERS].sort())
  const values = []
  for (const name of PLAYERS) {
    const player = snapshot.players[name]
    assert.equal(player.online, true); assert.equal(player.owned, true); assert.equal(player.op, false)
    assert.ok(['SURVIVAL', 'CREATIVE'].includes(player.gameMode)); assert.equal(player.world, snapshot.world.uuid)
    assert.ok(Number.isSafeInteger(player.heldSlot) && player.heldSlot >= 0 && player.heldSlot <= 8)
    assert.equal(player.inventory.length, 36); assert.equal(player.armor.length, 4)
    assert.deepEqual(player.cursor, {}, 'An action left a cursor item')
    values.push(...player.inventory, ...player.armor, player.offHand, player.cursor)
  }
  assert.ok(Array.isArray(snapshot.world.items)); const uuids = new Set()
  for (const item of snapshot.world.items) {
    assert.equal(item.world, snapshot.world.uuid)
    assert.equal(typeof item.uuid, 'string'); assert.ok(!uuids.has(item.uuid), 'Duplicate ground item entity')
    uuids.add(item.uuid); values.push(item)
  }
  return values
}
function holdings (snapshot) {
  const result = {}
  for (const item of allItems(snapshot)) {
    const key = itemKey(item)
    if (key !== null) result[key] = (result[key] || 0) + item.count
  }
  return Object.fromEntries(Object.entries(result).sort())
}
function difference (before, after) {
  const result = {}
  for (const key of new Set([...Object.keys(before), ...Object.keys(after)])) {
    const change = (after[key] || 0) - (before[key] || 0)
    if (change) result[key] = change
  }
  return result
}
function expectDelta (before, after, ranges = {}, tool = null) {
  const old = holdings(before), current = holdings(after)
  if (tool) {
    const first = tool.hand === 1 ? before.players[tool.actor].offHand : before.players[tool.actor].inventory[tool.slot]
    const last = tool.hand === 1 ? after.players[tool.actor].offHand : after.players[tool.actor].inventory[tool.slot]
    assert.equal(first.count, 1); assert.equal(last.count, 1)
    assert.equal(itemKey(first, true), itemKey(last, true), 'Tool changed components beyond durability')
    assert.equal(last.damage - first.damage, tool.damage, 'Unexpected tool durability cost')
    const from = itemKey(first), to = itemKey(last)
    old[from]--; if (!old[from]) delete old[from]
    old[to] = (old[to] || 0) + 1
  }
  const delta = difference(old, current), byId = {}
  for (const [key, amount] of Object.entries(delta)) {
    const id = JSON.parse(key).id, canonical = after.canonicalItems[id]
    assert.ok(canonical, 'Unregistered component delta: ' + id)
    assert.equal(key, itemKey(canonical), 'Output/material components differ from predeclared canonical item: ' + id)
    assert.ok(Object.hasOwn(ranges, id), 'Unrelated item changed: ' + id)
    byId[id] = (byId[id] || 0) + amount
  }
  for (const [id, [min, max]] of Object.entries(ranges)) {
    const amount = byId[id] || 0
    assert.ok(amount >= min && amount <= max, `${id} delta ${amount} outside ${min}..${max}`)
  }
  return byId
}
function assertCrop (block, crop, age, shape = true) {
  assert.equal(block.loaded, true); assert.equal(block.owned, true)
  assert.equal(block.id, NS + crop + '_crop'); assert.equal(block.age, age)
  assert.ok(Number.isSafeInteger(age) && age >= 0 && age <= 7)
  assert.equal(block.maintainsFarmland, true, 'Final native crop state does not maintain farmland')
  if (shape) {
    assert.deepEqual(block.nativeQueryErrors, [], 'Final native shape query failed')
    assert.deepEqual(block.shape, [[0, 0, 0, 1, (age + 2) / 16, 1]], 'Final native age selection shape differs')
    assert.deepEqual(block.collision, []); assert.deepEqual(block.supportShape, [])
  }
}
function assertAir (block) {
  assert.equal(block.loaded, true); assert.equal(block.owned, true)
  assert.equal(block.id, null); assert.equal(block.material, 'AIR'); assert.equal(block.age, null)
}
function nativeReloadReady (snapshot, expectedEpoch) {
  if (!snapshot || snapshot.epoch !== expectedEpoch + 1 || snapshot.ceReloading !== false || snapshot.canonicalReady !== true
      || snapshot.sourceCropBlocksReady !== true
      || !snapshot.canonicalItems || !Array.isArray(snapshot.fixture?.ages) || snapshot.fixture.ages.length !== 24) return false
  try {
    for (const id of ['tomato_seed', 'chili_seed', 'lettuce_seed', 'tomato', 'red_chili', 'green_chili', 'lettuce', 'caterpillar']) {
      if (!snapshot.canonicalItems[NS + id]) return false
    }
    for (let index = 0; index < 24; index++) {
      const state = snapshot.fixture.ages[index]
      assertCrop(state, CROPS[Math.floor(index / 8)], index % 8)
    }
    return true
  } catch {
    return false
  }
}
function playerState (snapshot) {
  return Object.fromEntries(PLAYERS.map(name => [name, Object.fromEntries(
    ['uuid', 'world', 'inventory', 'armor', 'offHand', 'cursor', 'heldSlot', 'gameMode', 'op'].map(k => [k, snapshot.players[name][k]]))]))
}
function persistentState (snapshot) {
  return Object.fromEntries(PERSISTED.map(name => [name, Object.fromEntries(
    ['position', 'id', 'age', 'shape', 'collision', 'supportShape', 'maintainsFarmland', 'rawBrightness', 'skyLight', 'support'].map(k => [k, snapshot.fixture[name][k]]))]))
}
function lootRanges (crop, age, fortune = 0) {
  assert.ok(Number.isSafeInteger(fortune) && fortune >= 0, 'Invalid Fortune level')
  // CE 26.9.1 CropDrops performs fortune + extra(3) Bernoulli trials,
  // each adding one seed to the initial count of one.
  const ranges = { [NS + crop + '_seed']: [1, age === 7 ? 4 + fortune : 1] }
  if (age === 7) {
    ranges[NS + (crop === 'chili' ? 'red_chili' : crop)] = [1, 1]
    if (crop === 'chili') ranges[NS + 'green_chili'] = [0, 1]
    if (crop === 'lettuce') ranges[NS + 'caterpillar'] = [0, 1]
  }
  return ranges
}
function assertFortune (crop, fortune, observedSeeds) {
  const [min, max] = lootRanges(crop, 7, fortune)[NS + crop + '_seed']
  assert.ok(Number.isSafeInteger(observedSeeds) && observedSeeds >= min && observedSeeds <= max,
    `${crop} seed count ${observedSeeds} outside Fortune ${fortune} bounds ${min}..${max}`)
  return observedSeeds
}
function assertNaturalEnvironment (snapshot, layout) {
  for (const crop of CROPS) {
    const block = snapshot.fixture[crop]
    assert.equal(block.rawBrightness, 9); assert.equal(block.skyLight, 0)
    assert.equal(block.support.material, 'FARMLAND')
    assert.equal(block.support.moisture, layout === 'stagedry' ? 0 : 7, 'Natural cohort support hydration differs')
    assert.equal(block.soil.length, 9, 'Native growth evidence must contain all nine neighbouring soil cells')
    for (const soil of block.soil) {
      assert.equal(soil.loaded, true); assert.equal(soil.owned, true)
      assert.equal(soil.material, 'FARMLAND')
      assert.equal(soil.moisture, layout === 'stagedry' ? 0 : 7)
    }
    const diagonal = block.soil.find(soil => at(soil.position, [POSITIONS[crop][0] - 1, 199, POSITIONS[crop][2] + 1]))
    assert.ok(diagonal, 'Missing measured diagonal neighbour')
    assert.equal(diagonal.cropAbove.id, layout === 'stagedense' ? NS + crop + '_crop' : null,
      'Dense/isolated native growth cohort differs')
  }
}
function auditHistory (previous, current) {
  assert.ok(Array.isArray(current.events) && Array.isArray(current.setupWrites))
  for (const key of ['events', 'setupWrites']) {
    const values = current[key]
    assert.ok(values.every((entry, i) => Number.isSafeInteger(entry.seq) && entry.seq > 0 && (!i || entry.seq > values[i - 1].seq)), 'Invalid cumulative ' + key)
    if (!previous) continue
    assert.ok(values.length >= previous[key].length, 'Truncated cumulative ' + key)
    for (let index = 0; index < previous[key].length; index++) {
      const before = previous[key][index], after = values[index]
      if (key === 'setupWrites') assert.deepEqual(after, before, 'Setup history was rewritten')
      else {
        for (const field of Object.keys(before)) {
          if (['after', 'finalObservedAt', 'finalProposed', 'finalDecisions', 'cancelled'].includes(field)) {
            if (before.finalObservedAt !== null) assert.deepEqual(after[field], before[field], 'Final native event receipt changed')
          } else assert.deepEqual(after[field], before[field], 'Native event prefix changed')
        }
      }
    }
  }
}
function validateCheckpoint (value, phase, runId) {
  assert.equal(value.schemaVersion, 1); assert.equal(value.scenario, 'sourcecrops')
  assert.equal(value.phase, phase); assert.equal(value.runId, runId)
  assert.deepEqual(Object.keys(value.players).sort(), [...PLAYERS].sort())
  assert.deepEqual(Object.keys(value.fixtures).sort(), [...PERSISTED].sort())
}

function matchCorePack (lock, checksum, byteLength, workflow) {
  assert.equal(lock.schema, 'source-crops-core-packs-v1')
  assert.ok(Array.isArray(lock.observations) && lock.observations.length > 0)
  assert.ok(Array.isArray(lock.reloads) && Array.isArray(lock.workflows))
  assert.ok(Number.isSafeInteger(workflow) && workflow >= 0 && workflow <= 2, 'Unexpected resource offer beyond two workflows')
  const latest = lock.observations.at(-1)
  assert.equal(latest.sequence, lock.observations.length)
  assert.ok(SHA.test(latest.sha256))
  assert.ok(Number.isSafeInteger(latest.bytes) && latest.bytes > 0)
  if (lock.workflows.length !== workflow || latest.sha256 !== checksum || latest.bytes !== byteLength) return false
  if (workflow) {
    const native = lock.workflows.at(-1), reload = lock.reloads.at(-1)
    assert.equal(native.sequence, workflow); assert.equal(native.command, 'ce workflow default')
    assertCoreWindow(native, 240000); assert.ok(SHA.test(native.beforeSha256))
    assert.equal(lock.reloads.length, workflow)
    assert.equal(reload.sequence, workflow); assert.equal(reload.afterEpoch, reload.beforeEpoch + 1)
    assertCoreWindow(reload, 90000)
    assert.ok(Number.isSafeInteger(reload.completedAtMillis) && reload.completedAtMillis <= native.issuedAtMillis)
  } else { assert.equal(lock.reloads.length, 0); assert.equal(latest.workflow, 0) }
  return { workflow, observation: latest.sequence }
}

function assertCoreWindow (value, duration) {
  assert.ok(Number.isSafeInteger(value.issuedAtMillis) && value.issuedAtMillis > 0)
  assert.equal(value.expiresAtMillis, value.issuedAtMillis + duration, 'Native operation does not have its fixed bounded window')
  if (value.completedAtMillis !== null) assert.ok(Number.isSafeInteger(value.completedAtMillis)
    && value.completedAtMillis >= value.issuedAtMillis && value.completedAtMillis <= value.expiresAtMillis,
  'Native operation completed outside its bounded window')
}

function completedCoreWorkflow (lock, round) {
  assert.equal(lock.schema, 'source-crops-core-packs-v1')
  const value = lock.workflows?.[round - 1]
  if (!value || value.completedAtMillis === null) return false
  assert.equal(lock.workflows.length, round); assert.equal(value.sequence, round)
  assertCoreWindow(value, 240000)
  assert.equal(lock.reloads.length, round)
  const reload = lock.reloads[round - 1]
  assertCoreWindow(reload, 90000)
  assert.equal(reload.afterEpoch, reload.beforeEpoch + 1)
  assert.ok(Number.isSafeInteger(reload.completedAtMillis) && reload.completedAtMillis <= value.issuedAtMillis)
  assert.ok(SHA.test(value.beforeSha256) && SHA.test(value.afterSha256))
  assert.equal(value.afterSha256, lock.observations.at(-1).sha256)
  assert.equal(typeof value.completionLog, 'string'); assert.ok(value.completionLog.length > 0)
  return value
}

function cancellationReceipt (before, after, kind, position, type, actor = null, hand = null) {
  const fresh = after.events.filter(event => event.seq > (before.events.at(-1)?.seq || 0) && at(event.position, position))
  const mutations = after.setupWrites.filter(write => write.seq > (before.setupWrites.at(-1)?.seq || 0)
    && write.action === 'applied:' + kind && at(write.position, position))
  assert.equal(mutations.length, 1, 'Cancellation was not actually injected exactly once at the intended block')
  const armSeq = mutations[0].after?.armSeq
  const arms = before.setupWrites.filter(write => write.action.startsWith('arm' + kind) && write.after?.sequence === armSeq)
  assert.equal(arms.length, 1, 'Cancellation lacks its own prior arm receipt')
  if (kind !== 'interact') assert.ok(at(arms[0].position, position), 'Cancellation arm targeted another block')
  const matching = fresh.filter(event => event.type === type)
  assert.equal(matching.length, 1, 'Expected exactly one native event of the requested type at the intended block')
  const event = matching[0]
  assert.notEqual(event.finalObservedAt, null, 'Native cancellation event was not finalized')
  assert.equal(event.cancelled, true, 'Native event at the intended block was not canceled')
  if (actor !== null) {
    assert.equal(event.playerUuid, after.players[actor].uuid, 'Native cancellation event belongs to another player')
    assert.equal(arms[0].after?.playerUuid, after.players[actor].uuid, 'Native cancellation arm belongs to another player')
  }
  if (hand !== null) {
    const expectedHand = hand === 1 ? 'OFF_HAND' : 'HAND'
    assert.equal(arms[0].after?.hand, expectedHand, 'Native cancellation arm used another hand')
    if (event.hand !== null && event.hand !== undefined) assert.equal(event.hand, expectedHand, 'Native cancellation event used another hand')
  }
  assert.ok(event.seq > mutations[0].seq && mutations[0].seq > arms[0].seq, 'Cancellation receipts are not causally ordered')
  return event
}

function boneResult (before, after, key, position, actor, kind, hand = 0) {
  const meal = value => hand === 1 ? value.players[actor].offHand : value.players[actor].inventory[3]
  assert.equal(meal(before).id, 'minecraft:bone_meal')
  const consumed = meal(before).count - (meal(after).count || 0)
  assert.ok([0, 1].includes(consumed), 'Bone meal use consumed an invalid count')
  const fresh = after.events.filter(event => event.seq > (before.events.at(-1)?.seq || 0) && at(event.position, position))
  assert.ok(fresh.every(event => event.finalObservedAt !== null), 'Bone meal includes an unsettled native receipt')
  const fertilizer = fresh.filter(event => event.type === 'BlockFertilizeEvent')
  if (['grow', 'interact'].includes(kind) || (kind === 'fertilize' && fertilizer.length)) {
    const type = { grow: 'BlockGrowEvent', interact: 'PlayerInteractEvent', fertilize: 'BlockFertilizeEvent' }[kind]
    cancellationReceipt(before, after, kind, position, type, kind === 'grow' ? null : actor,
      kind === 'grow' ? null : hand)
  }
  return { consumed, emitted: fertilizer.length > 0, age: after.fixture[key].age,
    cancellationAvailable: kind !== 'fertilize' || fertilizer.length > 0 }
}

async function scenario (h) {
  const { a, b, inspect, until, delay, Vec3, walkTo, report, out, phase, stateFile, consoleCommand } = h
  assert.ok(['core', 'seed', 'verify', 'empty'].includes(phase)); assert.ok(stateFile && path.isAbsolute(stateFile))
  const runId = path.basename(path.dirname(out)), verifiedFile = stateFile + '.verified.json'
  const result = report.checks.sourceCrops = { schema: 'source-crops-v1', runId, phase, cases: [], clientActions: [],
    groups: {}, open: ['Authenticated vanilla-client GPU/audio/outline acceptance', 'Forge soil/hooks and village recognition',
      'Default-speed maturity timing and source RNG sequence parity', 'Explosion decay and other non-player break sources',
      'A real third-party AntiGrief provider', 'Distribution hypothesis testing is separate from deterministic core bounds'] }
  let last = null, actionSeq = 0
  const read = async (bot = a, action = 'observe') => {
    if (!['core', 'seed'].includes(phase)) assert.ok(['observe', 'shapes', 'reloadstate', 'verifycheckpoint', 'sealverify', 'emptycheckpoint'].includes(action), 'Lifecycle phase attempted fixture mutation')
    const value = await inspect(bot, action)
    assert.equal(value.schema, 'source-crops-v1'); assert.equal(value.phase, phase); assert.equal(value.runId, runId)
    assert.equal(value.actor, bot.username); assert.equal(value.action, action); assert.deepEqual(value.errors, [])
    auditHistory(last, value); holdings(value)
    if (!['core', 'seed'].includes(phase)) assert.deepEqual(value.setupWrites, [], 'Recovery phase contains fixture or fault writes')
    assert.equal(value.world.name, 'acceptance-world'); assert.equal(value.world.owned, true); assert.equal(value.world.loaded, true)
    last = value
    return value
  }
  const compact = value => structuredClone({ fixture: value.fixture, players: value.players, world: value.world, epoch: value.epoch })
  const record = (caseId, before, after, start, assertions, extra = {}) => {
    const entry = { caseId, status: 'PASS', scope: 'PLAYER_PROTOCOL', actionKind: 'PLAYER_PROTOCOL',
      before: compact(before), after: compact(after), clientActions: result.clientActions.slice(start),
      setupWrites: after.setupWrites.filter(entry => !before.setupWrites.some(old => old.seq === entry.seq)),
      events: after.events.filter(entry => !before.events.some(old => old.seq === entry.seq)), assertions, ...extra }
    result.cases.push(entry); return entry
  }
  const action = async (bot, kind, position, perform, hand = 0) => {
    const entry = { seq: ++actionSeq, actor: bot.username, kind, actionKind: 'PLAYER_PROTOCOL', position: [...position],
      hand, slot: bot.quickBarSlot, wallTimeMillis: Date.now(), completed: false }
    result.clientActions.push(entry)
    await perform(); entry.completed = true
  }
  const approach = async (bot, position) => {
    // The crop columns are dry; the intervening irrigation columns have water.
    // Return through the fixed stone lane rather than cutting diagonally into it.
    await walkTo(bot, new Vec3(bot.entity.position.x, 200, 13.5), 'return to source crop stone lane', .3)
    await walkTo(bot, new Vec3(position[0] + .5, 200, 13.5), 'align with source crop dry column', .3)
    await walkTo(bot, new Vec3(position[0] + .5, 200, position[2] + 1.7), 'approach source crop ' + position.join(','), .3)
  }
  const select = async (bot, slot) => {
    bot.setQuickBarSlot(slot)
    await until('source crop held slot ' + slot, async () => (await read(bot)).players[bot.username].heldSlot === slot)
  }
  const offhand = async (bot, slot) => {
    const before = await read(), source = before.players[bot.username].inventory[slot]
    assert.ok(itemKey(source), 'Offhand transfer source is empty')
    const networkItem = await until('network inventory source for offhand equip', () => {
      const value = bot.inventory.slots[slot + 36]
      return value?.count === source.count ? value : false
    })
    await action(bot, 'equip-offhand', bot.entity.position.toArray(), () => bot.equip(networkItem, 'off-hand'), 1)
    const after = await until('offhand complete component transfer', async () => {
      const value = await read()
      assert.deepEqual(holdings(value), holdings(before), 'Offhand equip changed total components')
      return itemKey(value.players[bot.username].offHand) === itemKey(source) ? value : false
    })
    assert.deepEqual(after.players[bot.username].inventory[slot], {})
    await select(bot, 7)
  }
  const sendUse = async (bot, position, kind = 'use', hand = 0) => {
    await bot.lookAt(new Vec3(position[0] + .5, position[1] + .1, position[2] + .5), true)
    await action(bot, kind, position, async () => {
      bot._client.write('block_place', { location: new Vec3(...position), direction: 1, hand,
        cursorX: .5, cursorY: .1, cursorZ: .5, insideBlock: false, worldBorderHit: false, sequence: actionSeq })
      bot._client.write('arm_animation', { hand })
    }, hand)
  }
  const acknowledged = (before, after, position, bot, hand = null) => after.events.some(event => event.seq > (before.events.at(-1)?.seq || 0)
    && at(event.position, position) && event.playerUuid === before.players[bot.username].uuid && event.finalObservedAt !== null
    && (hand === null || event.type !== 'PlayerInteractEvent' || event.hand === (hand === 1 ? 'OFF_HAND' : 'HAND'))
    && ['PlayerInteractEvent', 'BlockPlaceEvent', 'BlockBreakEvent', 'SickleHarvestEvent'].includes(event.type))
  const assertUniqueActionReceipts = (events, position) => {
    const counts = new Map()
    for (const event of events) if (at(event.position, position)
      && ['PlayerInteractEvent', 'BlockPlaceEvent', 'BlockBreakEvent', 'SickleHarvestEvent'].includes(event.type)) {
      counts.set(event.type, (counts.get(event.type) || 0) + 1)
    }
    for (const [type, count] of counts) assert.equal(count, 1, 'Duplicate native action receipt: ' + type)
  }
  const use = async (bot, key, ready = () => true, options = {}) => {
    const position = options.position || POSITIONS[key]
    await approach(bot, POSITIONS[key] || position)
    const before = await read(), start = result.clientActions.length
    await sendUse(bot, position, options.kind || 'use', options.hand || 0)
    const after = await until('source crop action ' + key, async () => {
      const value = await read()
      const fresh = value.events.filter(event => event.seq > (before.events.at(-1)?.seq || 0) && at(event.position, position))
      assertUniqueActionReceipts(fresh, position)
      return acknowledged(before, value, position, bot, options.hand || 0) && fresh.every(event => event.finalObservedAt !== null)
        && ready(value) ? value : false
    })
    return { before, after, start }
  }
  const dig = async (bot, key) => {
    const position = POSITIONS[key]; await approach(bot, position)
    const before = await read(), start = result.clientActions.length
    const block = await until('network crop to dig', () => bot.blockAt(new Vec3(...position)))
    await action(bot, 'dig', position, () => bot.dig(block, true))
    const after = await until('native crop break committed', async () => {
      const value = await read()
      return value.fixture[key].material === 'AIR' && acknowledged(before, value, position, bot) ? value : false
    })
    assertAir(after.fixture[key]); return { before, after, start }
  }
  const pickup = async baseline => {
    let value = await read()
    const expected = holdings(baseline)
    for (const drop of value.world.items) {
      const target = await until('network drop or recorded automatic pickup', async () => {
        const snapshot = await read()
        assert.deepEqual(holdings(snapshot), expected, 'Waiting for a network drop changed total components')
        if (!snapshot.world.items.some(item => item.uuid === drop.uuid)) return { collected: true }
        const entity = Object.values(a.entities).find(entity => entity.uuid === drop.uuid)
        return entity ? { entity } : false
      })
      if (target.entity) await action(a, 'walk-pickup', drop.position, () =>
        walkTo(a, () => target.entity.position, 'physically collect source crop drop', .25,
          () => a.entities[target.entity.id] !== target.entity))
    }
    value = await until('all source crop drops physically collected', async () => {
      const value = await read()
      assert.deepEqual(holdings(value), expected, 'Pickup changed total components or quantity')
      return value.world.items.length === 0 ? value : false
    })
    return value
  }
  const stage = async (name, fortune = false) => {
    assert.ok(['core', 'seed'].includes(phase))
    await read(a, name)
    await read(a, fortune ? 'stockfortune' : 'stock'); await read(b, 'stockempty')
    return read()
  }
  const measureLight = async (level, keys = CROPS) => until('measured native raw light ' + level, async () => {
    const value = await read()
    return keys.every(crop => value.fixture[crop].rawBrightness === level && value.fixture[crop].skyLight === 0) ? value : false
  }, 30000)
  const seal = (file, value) => fs.writeFileSync(file, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' })
  const verifyPersisted = (value, expected) => {
    assert.deepEqual(playerState(value), expected.players, 'Persistent player inventory/components drifted')
    assert.deepEqual(persistentState(value), expected.fixtures, 'Persistent crop identity/age/environment/native shape drifted')
    assert.deepEqual(value.world.items, [], 'Unexpected persistent loose items')
  }

  if (phase === 'verify' || phase === 'empty') {
    const seedBytes = fs.readFileSync(stateFile), seed = JSON.parse(seedBytes)
    validateCheckpoint(seed, 'seed', runId)
    const seedHash = hash(seedBytes)
    let expected = seed
    if (phase === 'empty') {
      expected = JSON.parse(fs.readFileSync(verifiedFile))
      validateCheckpoint(expected, 'verify', runId)
      assert.equal(expected.seedStateSha256, seedHash, 'Recovery belongs to another seed')
    } else assert.ok(!fs.existsSync(verifiedFile), 'Refusing to replace a recovery checkpoint')
    const initial = await read(a, phase === 'verify' ? 'verifycheckpoint' : 'emptycheckpoint')
    assert.equal(initial.checkpoint.matched, true); assert.deepEqual(initial.checkpoint.differences, [])
    verifyPersisted(initial, expected)
    if (phase === 'verify') {
      for (const key of PERSISTED) assertCrop(initial.fixture[key], cropOf(key), 7)
      for (const key of PERSISTED) {
        const crop = cropOf(key), oldAge = initial.fixture[key].age
        await select(a, 7)
        const value = await dig(a, key)
        expectDelta(value.before, value.after, lootRanges(crop, oldAge))
        const after = await pickup(value.after)
        record('restart-recover-' + key, value.before, after, value.start, ['Actual protocol dig', 'Correct source loot', 'Complete component conservation and physical pickup'])
      }
      const final = await read(a, 'sealverify')
      assert.equal(final.checkpoint.created, true)
      for (const key of PERSISTED) assertAir(final.fixture[key])
      assert.deepEqual(final.world.items, [])
      seal(verifiedFile, { schemaVersion: 1, scenario: 'sourcecrops', phase, runId, seedStateSha256: seedHash,
        players: playerState(final), fixtures: persistentState(final) })
      report.seedStateSha256 = seedHash; report.verifiedStateSha256 = hash(fs.readFileSync(verifiedFile))
    } else {
      for (const key of PERSISTED) {
        assertAir(initial.fixture[key]); await select(a, 7)
        await approach(a, POSITIONS[key])
        await sendUse(a, [POSITIONS[key][0], 199, POSITIONS[key][2]], 'empty-position-use')
      }
      await delay(2000)
      const final = await read()
      assert.deepEqual(holdings(final), holdings(initial), 'Empty positions recreated old products')
      for (const key of PERSISTED) assertAir(final.fixture[key])
      assert.deepEqual(final.world.items, [])
      result.groups.noResurrection = { status: 'PASS', elapsedObservationMs: 2000 }
    }
    result.groups.restart = { status: 'PASS', phase, seedStateSha256: seedHash }
    return
  }

  assert.ok(!fs.existsSync(stateFile) && !fs.existsSync(verifiedFile), 'Refusing to overwrite an earlier crop run')
  if (phase === 'core') {
  // Warm native state caches before two real CE resource reloads.
  let value = await stage('stageages')
  assert.equal(value.fixture.ages.length, 24)
  for (let index = 0; index < 24; index++) assertCrop(value.fixture.ages[index], CROPS[Math.floor(index / 8)], index % 8)
  result.groups.nativeShapes = { status: 'PASS', states: 24, path: 'Final native state queries, including caches' }
  for (let round = 0; round < 2; round++) {
    const before = await read(a, 'reloadstate'), epoch = before.epoch
    assert.equal(typeof consoleCommand, 'function')
    consoleCommand('ce reload config')
    value = await until('real CraftEngine reload, registry readiness and native crop cache', async () => {
      let snapshot
      try { snapshot = await read(a, 'reloadstate') } catch (failure) {
        // Console polling may overlap the reload before its completion event.
        // New probes return a read-only not-ready report during that window.
        if (/Missing fixture item (?:tomato_seed|chili_seed|lettuce_seed|tomato|red_chili|green_chili|lettuce|caterpillar)/.test(String(failure))) return false
        throw failure
      }
      return nativeReloadReady(snapshot, epoch) ? snapshot : false
    }, 90000)
    // A reload command is lifecycle evidence only after a genuine server event.
    const lockFile = path.join(out, 'resource-pack-lock.json')
    const beforePack = JSON.parse(fs.readFileSync(lockFile)).observations.at(-1).sha256
    const packs = Object.fromEntries(PLAYERS.map(name => [name, report.packHistory?.[name]?.length || 0]))
    consoleCommand('ce workflow default')
    const workflow = await until('real workflow completion, resource reoffer and official reader completion', () => {
      const completed = completedCoreWorkflow(JSON.parse(fs.readFileSync(lockFile)), round + 1)
      return completed && PLAYERS.every(name => (report.packHistory?.[name]?.length || 0) === packs[name] + 1
        && report.packHistory[name].at(-1).workflow === round + 1) ? completed : false
    }, 240000)
    const afterPack = workflow.afterSha256
    for (const name of PLAYERS) assert.equal(report.packHistory[name].at(-1).packSha256, afterPack)
    value = await read(a, 'shapes')
    for (let index = 0; index < 24; index++) assertCrop(value.fixture.ages[index], CROPS[Math.floor(index / 8)], index % 8)
    result.cases.push({ caseId: 'reload-warmed-native-shapes-' + round, status: 'PASS', scope: 'LIFECYCLE',
      actionKind: 'LIFECYCLE', beforeEpoch: epoch, afterEpoch: value.epoch, beforePackSha256: beforePack, afterPackSha256: afterPack, resourceOffersBefore: packs,
      resourceOffersAfter: Object.fromEntries(PLAYERS.map(name => [name, report.packHistory?.[name]?.length || 0])) })
  }
  result.groups.reload = { status: 'PASS', genuineEpochIncrements: 2, genuineWorkflowAndOfficialResourceReoffers: 2 }

  value = await stage('stageplant')
  for (const [index, crop] of CROPS.entries()) {
    assertAir(value.fixture[crop]); await select(a, index)
    const planted = await use(a, crop, snap => snap.fixture[crop].id === NS + crop + '_crop',
      { position: [POSITIONS[crop][0], 199, POSITIONS[crop][2]], kind: 'plant-seed' })
    assertCrop(planted.after.fixture[crop], crop, 0)
    expectDelta(planted.before, planted.after, { [NS + crop + '_seed']: [-1, -1] })
    record('protocol-plant-' + crop, planted.before, planted.after, planted.start, ['Previously AIR', 'Real seed placement', 'Exactly one canonical seed consumed'])
    value = planted.after
  }
  result.groups.planting = { status: 'PASS', crops: 3 }
  for (const layout of ['stageplant', 'stageimmature']) {
    await stage(layout); await select(a, 7)
    for (const crop of CROPS) {
      if (layout === 'stageplant') {
        const index = CROPS.indexOf(crop); await select(a, index)
        await use(a, crop, snap => snap.fixture[crop].age === 0, { position: [POSITIONS[crop][0], 199, POSITIONS[crop][2]], kind: 'plant-seed' })
        await select(a, 7)
      }
      const tested = await use(a, crop)
      assertCrop(tested.after.fixture[crop], crop, layout === 'stageplant' ? 0 : 6)
      expectDelta(tested.before, tested.after)
      record('immature-use-' + layout + '-' + crop, tested.before, tested.after, tested.start, ['No immature yield or age reset'])
    }
  }
  await stage('stageinvalid')
  for (const [index, crop] of CROPS.entries()) {
    await select(a, index)
    const tested = await use(a, crop, () => true, { position: [POSITIONS[crop][0], 199, POSITIONS[crop][2]], kind: 'invalid-soil-or-occupied-placement' })
    expectDelta(tested.before, tested.after)
    if (crop === 'lettuce') assertCrop(tested.after.fixture[crop], crop, 0)
    else assertAir(tested.after.fixture[crop])
    record('invalid-placement-' + crop, tested.before, tested.after, tested.start, ['Stone/dirt/occupied target preserves seed and existing crop'])
  }

  for (const mode of ['main', 'offhand', 'tiledrops', 'concurrent']) {
    await stage('stagepick'); await select(a, 7); await select(b, 7)
    if (mode === 'tiledrops') await read(a, 'tiledropsoff')
    try {
      for (const crop of CROPS) {
        let tested
        if (mode === 'concurrent' && crop !== 'lettuce') {
          await Promise.all([approach(a, POSITIONS[crop]), approach(b, POSITIONS[crop])])
          const before = await read(), start = result.clientActions.length
          await Promise.all([sendUse(a, POSITIONS[crop]), sendUse(b, POSITIONS[crop])])
          const after = await until('one concurrent ripe harvest', async () => {
            const snap = await read(); return snap.fixture[crop].age === 5 && acknowledged(before, snap, POSITIONS[crop], a)
              && acknowledged(before, snap, POSITIONS[crop], b) ? snap : false
          })
          tested = { before, after, start }
        } else tested = await use(a, crop, snap => crop === 'lettuce' || mode === 'offhand' || snap.fixture[crop].age === 5,
          { hand: mode === 'offhand' ? 1 : 0 })
        const picking = crop !== 'lettuce'
        assertCrop(tested.after.fixture[crop], crop, picking ? 5 : 7)
        const ranges = !picking || mode === 'tiledrops' ? {} : crop === 'chili'
          ? { [NS + 'red_chili']: [0, 1], [NS + 'green_chili']: [0, 1] } : { [NS + 'tomato']: [1, 1] }
        const delta = expectDelta(tested.before, tested.after, ranges)
        if (picking && crop === 'chili' && mode !== 'tiledrops') assert.equal((delta[NS + 'red_chili'] || 0) + (delta[NS + 'green_chili'] || 0), 1)
        const after = await pickup(tested.after)
        record('ripe-pick-' + mode + '-' + crop, tested.before, after, tested.start, ['Correct age5/PASS split', 'Exactly bounded canonical output', 'Two-player component conservation'])
        if (picking) {
          const repeated = await use(a, crop)
          assertCrop(repeated.after.fixture[crop], crop, 5); expectDelta(repeated.before, repeated.after)
          record('repeat-age5-' + mode + '-' + crop, repeated.before, repeated.after, repeated.start, ['No repeated unripe output'])
        }
      }
    } finally { if (mode === 'tiledrops') await read(a, 'tiledropson') }
  }
  result.groups.picking = { status: 'PASS', mainHand: true, offhand: true, repeat: true, concurrency: true, doTileDrops: true }

  for (const mode of ['normal', 'fortune', 'creative', 'offhand']) {
    await stage('stagesickle', mode === 'fortune')
    if (mode === 'creative') await read(a, 'creative')
    try {
      for (const crop of CROPS) {
        if (mode === 'offhand' && crop === CROPS[0]) await offhand(a, 4)
        else if (mode !== 'offhand') await select(a, 4)
        const tested = await use(a, crop, snap => snap.fixture[crop].age === 0, { kind: 'sickle-use', hand: mode === 'offhand' ? 1 : 0 })
        assertCrop(tested.after.fixture[crop], crop, 0)
        const settings = tested.before.world.sickle
        assert.ok(!settings.error && Number.isSafeInteger(settings.radius) && settings.radius >= 0 && settings.radius < 4,
          'This fixed independent-crop fixture requires a verified sickle radius below four')
        const damage = mode === 'creative' || !settings.damageItem ? 0 : settings.damagePerUse
        expectDelta(tested.before, tested.after, lootRanges(crop, 7), { actor: a.username, slot: 4, hand: mode === 'offhand' ? 1 : 0, damage })
        const after = await pickup(tested.after)
        record('sickle-empty-tool-loot-' + mode + '-' + crop, tested.before, after, tested.start,
          ['Real sickle protocol use', 'Mature crop reset to age0', 'Empty-tool seeds 1..4 even FortuneIII', 'Correct per-use durability'])
        await delay(Math.max(0, settings.cooldownTicks) * 50 + 100)
        const repeated = await use(a, crop, () => true, { hand: mode === 'offhand' ? 1 : 0 })
        expectDelta(repeated.before, repeated.after); assertCrop(repeated.after.fixture[crop], crop, 0)
      }
    } finally { if (mode === 'creative') await read(a, 'survival') }
  }
  result.groups.sickle = { status: 'PASS', fortuneUsesEmptyTool: true, creative: true, offhand: true }

  for (const setup of [{ layout: 'stageplant', age: 0 }, { layout: 'stageimmature', age: 6 },
    { layout: 'stagebreak', age: 7 }, { layout: 'stagebreak', age: 7, fortune: 3 }]) {
    await stage(setup.layout)
    for (const [index, crop] of CROPS.entries()) {
      if (setup.age === 0) {
        await select(a, index)
        await use(a, crop, snap => snap.fixture[crop].age === 0, { position: [POSITIONS[crop][0], 199, POSITIONS[crop][2]], kind: 'plant-seed' })
      }
      await select(a, setup.fortune ? 5 : 7)
      const tested = await dig(a, crop)
      expectDelta(tested.before, tested.after, lootRanges(crop, setup.age, setup.fortune || 0),
        setup.fortune ? { actor: a.username, slot: 5, damage: 0 } : null)
      const after = await pickup(tested.after)
      record('ordinary-dig-age' + setup.age + '-fortune' + (setup.fortune || 0) + '-' + crop,
        tested.before, after, tested.start, ['Actual dig removes crop', 'Seed/fruit bounds including FortuneIII', 'Physical pickup and full component conservation'])
    }
  }
  result.groups.ordinaryBreak = { status: 'PASS', ages: [0, 6, 7], fortune: true }

  // Calibrate cancellation consumption on this platform's real native wheat path.
  const boneControls = {}
  for (const kind of ['success', 'mature', 'grow', 'fertilize', 'interact']) {
    await stage(kind === 'mature' ? 'stagemature' : 'stagebone'); await select(a, 3)
    if (['grow', 'fertilize', 'interact'].includes(kind)) await read(a, 'arm' + kind + (kind === 'interact' ? '' : 'wheat'))
    const tested = await use(a, 'wheat', () => true, { kind: 'bone-meal-native-control' })
    const control = boneResult(tested.before, tested.after, 'wheat', POSITIONS.wheat, a.username, kind)
    const { consumed, emitted } = control
    if (kind === 'success') { assert.ok(tested.after.fixture.wheat.age >= 2 && tested.after.fixture.wheat.age <= 5); assert.equal(consumed, 1) }
    if (kind === 'mature') { assert.equal(tested.after.fixture.wheat.age, 7); assert.equal(consumed, 0) }
    if (kind === 'grow' || kind === 'interact') assert.equal(tested.after.fixture.wheat.age, 0)
    expectDelta(tested.before, tested.after, { 'minecraft:bone_meal': [-consumed, -consumed] })
    if (kind === 'fertilize' && emitted) assert.equal(tested.after.fixture.wheat.age, 0)
    boneControls[kind] = control
    record('bone-native-control-' + kind, tested.before, tested.after, tested.start,
      ['Actual native wheat control', 'Consumption recorded independently of cancellation'],
      { status: control.cancellationAvailable ? 'PASS' : 'SKIP_PRECONDITION_UNAVAILABLE', fertilizer: emitted ? 'EMITTED' : 'NOT_EMITTED' })
    await read(a, 'disarm')
  }
  for (const crop of CROPS) for (const kind of ['success', 'mature', 'grow', 'fertilize', 'interact', 'offhand-success', 'offhand-mature']) {
    const hand = kind.startsWith('offhand-') ? 1 : 0, operation = kind.replace('offhand-', '')
    await stage(operation === 'mature' ? 'stagemature' : 'stagebone')
    if (hand) await offhand(a, 3)
    else await select(a, 3)
    if (['grow', 'fertilize', 'interact'].includes(operation)) await read(a, 'arm' + operation + (operation === 'interact' ? '' : crop))
    const tested = await use(a, crop, () => true, { kind: 'bone-meal', hand })
    const observed = boneResult(tested.before, tested.after, crop, POSITIONS[crop], a.username, operation, hand)
    const { consumed } = observed
    assert.equal(consumed, boneControls[operation].consumed, 'Custom/native bone-meal consumption differs; requires substantive review')
    const age = tested.after.fixture[crop].age
    if (operation === 'success') assert.ok(age >= 2 && age <= 5)
    else if (operation === 'mature') assert.equal(age, crop === 'lettuce' ? 7 : 5, 'Mature source fruit-picking takes priority over bone meal')
    else if (operation === 'grow' || operation === 'interact') assert.equal(age, 0)
    if (operation === 'fertilize') {
      assert.equal(observed.emitted, boneControls[operation].emitted, 'Custom/native BlockFertilizeEvent availability differs')
      if (observed.emitted) assert.equal(age, 0, 'Canceled native fertilizer result grew a crop')
      else assert.ok(age >= 2 && age <= 5, 'Unavailable fertilizer event must retain the observed native success bounds')
    }
    const ranges = { 'minecraft:bone_meal': [-consumed, -consumed] }
    if (operation === 'mature' && crop !== 'lettuce') Object.assign(ranges, crop === 'chili'
      ? { [NS + 'red_chili']: [0, 1], [NS + 'green_chili']: [0, 1] } : { [NS + 'tomato']: [1, 1] })
    const delta = expectDelta(tested.before, tested.after, ranges)
    if (operation === 'mature' && crop === 'chili') assert.equal((delta[NS + 'red_chili'] || 0) + (delta[NS + 'green_chili'] || 0), 1)
    const after = await read(a, 'disarm')
    record('bone-' + kind + '-' + crop, tested.before, after, tested.start,
      ['Real bone meal use', 'Source age/cancellation boundary', 'Native-control consumption comparison'],
      { status: observed.cancellationAvailable ? 'PASS' : 'SKIP_PRECONDITION_UNAVAILABLE' })
  }
  result.groups.bonemeal = { status: boneControls.fertilize.cancellationAvailable ? 'PASS' : 'PARTIAL',
    nativeControls: boneControls, consumptionNotAssumedRefunded: true }

  for (const { fault, hand } of ['slot', 'count', 'item', 'empty', 'crop'].map(fault => ({ fault, hand: 0 }))
    .concat(['count', 'item', 'empty'].map(fault => ({ fault, hand: 1 })))) {
    await stage('stagepick')
    if (hand) await offhand(a, 6)
    else await select(a, 6)
    await read(a, 'arm' + (hand ? 'off' : '') + fault)
    const tested = await use(a, 'tomato', () => true, { hand })
    const injections = tested.after.setupWrites.filter(write => write.seq > (tested.before.setupWrites.at(-1)?.seq || 0)
      && write.action === 'mutate:' + fault)
    assert.equal(injections.length, 1, 'Declared source mutation was not applied exactly once')
    const normalized = structuredClone(tested.before)
    normalized.players[a.username] = structuredClone(injections[0].after)
    expectDelta(normalized, tested.after)
    if (fault === 'crop') assertAir(tested.after.fixture.tomato)
    else assertCrop(tested.after.fixture.tomato, 'tomato', 7)
    await read(a, 'disarm')
    record('logging-callback-source-drift-' + (hand ? 'offhand-' : '') + fault, tested.before, tested.after, tested.start,
      ['One reachable callback fault', 'No stale harvest commit or output', 'Only recorded fixture mutation excluded from conservation'])
  }
  await stage('stagesickle'); await select(a, 4); await read(a, 'armsickle')
  const canceledSickle = await use(a, 'tomato')
  assertCrop(canceledSickle.after.fixture.tomato, 'tomato', 7); expectDelta(canceledSickle.before, canceledSickle.after)
  record('sickle-event-takeover-cancel', canceledSickle.before, canceledSickle.after, canceledSickle.start,
    ['Real SickleHarvestEvent takeover', 'No age/output/durability commit'])
  await read(a, 'disarm')
  result.groups.callbacks = { status: 'PASS', sourceDrift: 8, offhandSourceDrift: 3, sickleTakeover: true, protectionProvider: 'NOT_TESTED' }

  const naturalResults = []
  for (const layout of ['stagewet', 'stagedry', 'stagedense']) {
    await stage(layout); await read(a, 'stagelight9'); await measureLight(9)
    const before = await read(), start = result.clientActions.length
    assertNaturalEnvironment(before, layout)
    const armed = await read(a, 'armgrowtomato'); await read(a, 'naturalstart')
    let final
    try {
      final = await until('real random tick maturity ' + layout, async () => {
        const snapshot = await read()
        return CROPS.every(crop => snapshot.fixture[crop].age === 7)
          && snapshot.events.filter(e => e.seq > (before.events.at(-1)?.seq || 0)
            && CROPS.some(crop => at(e.position, POSITIONS[crop]))).every(e => e.finalObservedAt !== null) ? snapshot : false
      }, 240000)
    } finally { await read(a, 'naturalstop'); await read(a, 'disarm') }
    const grows = final.events.filter(e => e.type === 'BlockGrowEvent' && e.seq > (before.events.at(-1)?.seq || 0))
    cancellationReceipt(armed, final, 'grow', POSITIONS.tomato, 'BlockGrowEvent')
    assertNaturalEnvironment(final, layout)
    for (const crop of CROPS) {
      const events = grows.filter(e => at(e.position, POSITIONS[crop]))
      assert.ok(events.filter(e => !e.cancelled).length >= 7, 'Maturity lacks actual natural +1 events')
      for (const event of events) {
        assert.equal(event.proposed.age, event.before.age + 1, 'Natural growth skipped an age')
        assert.equal(event.after.age, event.before.age + (event.cancelled ? 0 : 1), 'Native natural receipt did not commit its declared +1/cancellation')
      }
      assertCrop(final.fixture[crop], crop, 7)
    }
    expectDelta(before, final)
    naturalResults.push({ layout, rawBrightness: 9, randomTickSpeed: 64, naturalEvents: grows.length })
    record('natural-random-tick-' + layout, before, final, start, ['Actual random ticks, no behavior calls', 'Measured raw9/sky0', 'Every committed growth step +1', 'Maturity and one canceled natural event'],
      { scope: 'NATIVE_RANDOM_TICK', actionKind: 'OBSERVATION' })
  }
  result.groups.naturalGrowth = { status: 'PASS', cohorts: naturalResults, probabilityFormula: 'STATIC_AND_UNIT_ONLY' }
  await stage('stagenatural'); await read(a, 'stagelight8'); await measureLight(8)
  const lowBefore = await read(); await read(a, 'naturalstart')
  let lowAfter
  try { await delay(15000); lowAfter = await read() } finally { await read(a, 'naturalstop') }
  for (const crop of CROPS) assertCrop(lowAfter.fixture[crop], crop, 0)
  expectDelta(lowBefore, lowAfter)
  result.groups.lowLight = { status: 'PASS', rawBrightness: 8, skyLight: 0, elapsedObservationMs: 15000,
    positiveControls: naturalResults, claim: 'Finite negative observation with separate raw9 positive controls; not a proof of probability formula' }

  for (const layout of ['stageimmature', 'stagebreak']) {
    const before = await stage(layout), start = result.clientActions.length
    await read(a, 'supportstone')
    const after = await until('native lost-farmland survival', async () => {
      const snapshot = await read(); return CROPS.every(crop => snapshot.fixture[crop].material === 'AIR') ? snapshot : false
    })
    const ranges = Object.assign({}, ...CROPS.map(crop => lootRanges(crop, layout === 'stagebreak' ? 7 : 6)))
    expectDelta(before, after, ranges)
    await pickup(after)
    record('support-loss-' + layout, before, after, start, ['Actual support update', 'Unique source loot', 'No ghost CE states'],
      { scope: 'FIXTURE_SETUP_AND_NATIVE_SURVIVAL', actionKind: 'FIXTURE_SETUP', sourceTimingDifference: 'OPEN: CE one-tick delayed path' })
  }
  await stage('stageboundary')
  const boundary = await read()
  const unknown = boundary.fixture.boundary.some(block => block.soil?.some(soil => soil.loaded === false || soil.owned === false))
  result.groups.foliaBoundary = { status: unknown ? 'NOT_TESTED' : 'SKIP_PRECONDITION_UNAVAILABLE',
    reason: unknown ? 'Unknown neighbour observed; recovery movement and live boundary behavior still require a separate controlled case'
      : 'Actual Folia neighbour unknown/unloaded precondition unavailable; crossing a chunk number is insufficient', snapshots: boundary.fixture.boundary }
  result.groups.clientVisuals = { status: 'NOT_TESTED', reason: 'Protocol and headless decode do not render/audio-accept vanilla client behavior' }
  result.acceptanceScope = 'Only individual PASS cases; NOT_TESTED/SKIP groups remain open and never count as passing'
  return
  }

  // Persistent crops are all protocol planted; initial age0 is never fixture-created.
  const seedArena = await stage('stageplant')
  for (const key of PERSISTED) assertAir(seedArena.fixture[key])
  for (const key of PERSISTED) {
    const crop = cropOf(key), index = CROPS.indexOf(crop); await select(a, index)
    const planted = await use(a, key, snapshot => snapshot.fixture[key].age === 0,
      { position: [POSITIONS[key][0], 199, POSITIONS[key][2]], kind: 'persistent-plant-seed' })
    assertAir(planted.before.fixture[key])
    assertCrop(planted.after.fixture[key], crop, 0)
    expectDelta(planted.before, planted.after, { [NS + crop + '_seed']: [-1, -1] })
    record('restart-seed-plant-' + key, planted.before, planted.after, planted.start,
      ['Actual seed protocol placement', 'Age0 native source crop', 'Exactly one canonical seed consumed'])
  }
  for (const key of PERSISTED) {
    const crop = cropOf(key)
    await select(a, 3)
    for (let attempt = 0; attempt < 4; attempt++) {
      if ((await read()).fixture[key].age === 7) break
      const grown = await use(a, key, () => true, { kind: 'persistent-bone-meal' })
      assert.ok(grown.after.fixture[key].age > grown.before.fixture[key].age)
      assert.ok(grown.after.fixture[key].age <= Math.min(7, grown.before.fixture[key].age + 5)
        && grown.after.fixture[key].age >= Math.min(7, grown.before.fixture[key].age + 2))
      expectDelta(grown.before, grown.after, { 'minecraft:bone_meal': [-1, -1] })
      record('restart-seed-bone-' + key + '-' + attempt, grown.before, grown.after, grown.start,
        ['Actual bone meal protocol use', 'Native age advance2..5 capped7', 'Exactly one bone meal consumed'])
    }
    assertCrop((await read()).fixture[key], crop, 7)
    await select(a, 7)
  }
  await read(a, 'stagelight8'); await measureLight(8, PERSISTED); await read(a, 'disarm')
  const final = await read(a, 'checkpoint')
  assert.equal(final.checkpoint.created, true); assert.deepEqual(final.world.items, [])
  for (const key of PERSISTED) {
    assertCrop(final.fixture[key], cropOf(key), 7)
    assert.equal(final.fixture[key].support.material, 'FARMLAND')
    assert.equal(final.fixture[key].support.moisture, 7, 'Persistent crop must have measured hydrated native farmland')
  }
  seal(stateFile, { schemaVersion: 1, scenario: 'sourcecrops', phase, runId, players: playerState(final), fixtures: persistentState(final),
    setupCheckpoint: final.checkpoint, coreCases: result.cases.map(entry => entry.caseId), scope: 'Six real protocol-planted crops and complete two-player components' })
  result.groups.restartSeed = { status: 'PASS', realPlantedCrops: 6, fixtureMaturity: false, seedStateSha256: hash(fs.readFileSync(stateFile)) }
  result.groups.clientVisuals = { status: 'NOT_TESTED', reason: 'Protocol and headless decode do not render/audio-accept vanilla client behavior' }
  result.acceptanceScope = 'Only individual PASS cases; NOT_TESTED/SKIP groups remain open and never count as passing'
}

module.exports = scenario
module.exports.contract = { NS, PLAYERS, CROPS, POSITIONS, PERSISTED, itemKey, holdings, difference, expectDelta,
  assertCrop, assertAir, lootRanges, assertFortune, auditHistory, validateCheckpoint, playerState, persistentState,
  matchCorePack, completedCoreWorkflow, boneResult, cancellationReceipt, nativeReloadReady, assertNaturalEnvironment }
