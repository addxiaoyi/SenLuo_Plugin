'use strict'
// Real player protocol actions; console commands only arrange seed fixtures and observe server state.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { createHash } = require('node:crypto')

const NAMES = ['chair', 'board', 'molotov']
const IDS = {
  chair: 'kaleidoscope_deco:chair/oak',
  board: 'kaleidoscope_tavern:base_sandwich_board',
  molotov: 'kaleidoscope_tavern:molotov'
}
const RECOVERED = { ...IDS, board: 'kaleidoscope_tavern:poppy_sandwich_board' }
const POSITIONS = { chair: [678.5, 200, 9.5], board: [682.5, 200, 9.5], molotov: [680.5, 200, 6.5] }
const TEXT = '今日菜单 \\\\ <招牌>\n第二行 中文'
const DEFAULT_BOARD = { style: 'base', text: '', color: 'white', alignment: 'CENTER', waxed: false, glowing: false }
const FINAL_BOARD = { style: 'poppy', text: TEXT, color: 'red', alignment: 'RIGHT', waxed: true, glowing: true }
const STOCK = { 0: ['minecraft:red_carpet', 3], 1: ['minecraft:shears', 1], 2: ['minecraft:red_dye', 3],
  3: ['minecraft:glow_ink_sac', 2], 4: ['minecraft:honeycomb', 2], 5: ['minecraft:poppy', 2], 8: ['minecraft:blue_carpet', 2] }
const clone = value => structuredClone(value)
const record = value => value !== null && typeof value === 'object' && !Array.isArray(value)
const empty = value => record(value) && Object.keys(value).length === 0
const canonical = value => Array.isArray(value) ? value.map(canonical) : record(value)
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])])) : value
const same = (a, b) => JSON.stringify(canonical(a)) === JSON.stringify(canonical(b))
const sha256 = value => createHash('sha256').update(value).digest('hex')

// 1.21.11's native UNTRUSTED_TAG_CODEC is optionalTagCodec().apply(lengthPrefixed(65536)).
// The pinned minecraft-data schema instead has a boolean option and no byte length.
// Override only that common packet; anonOptionalNbt uses TAG_END for an absent tag.
const CUSTOM_PACKETS_12111 = { '1.21': { types: {
  packet_common_custom_click_action: ['container', [
    { name: 'id', type: 'string' }, { name: 'payloadLength', type: 'varint' },
    { name: 'nbt', type: 'anonOptionalNbt' }
  ]]
} } }

function nativeDialogSubmission (submission, serializer) {
  const payloadLength = serializer.proto.sizeOf(submission.nbt, 'anonOptionalNbt')
  assert.ok(Number.isInteger(payloadLength) && payloadLength >= 1 && payloadLength <= 65536,
    'Native Dialog NBT exceeds the protocol byte limit')
  return { id: submission.id, payloadLength, nbt: clone(submission.nbt) }
}
function packetEvidence (value) {
  const buffer = Buffer.from(value), result = {
    bytes: buffer.length, firstBytesHex: buffer.subarray(0, 12).toString('hex'),
    sha256: sha256(buffer), base64: buffer.toString('base64')
  }
  let header = 0
  for (let i = 0; i < Math.min(5, buffer.length); i++) {
    header |= (buffer[i] & 0x7f) << (7 * i)
    if ((buffer[i] & 0x80) === 0) return { ...result, firstVarInt: header, firstVarIntBytes: i + 1 }
  }
  return { ...result, firstVarIntError: 'Missing or oversized VarInt header' }
}
function traceDialogSubmission (bot, submission, report) {
  const client = bot._client, serializer = client.serializer, framer = client.framer
  const compressionThreshold = () => client.compressor?.compressionThreshold ?? null
  const details = {
    packetName: 'custom_click_action', state: client.state ?? null,
    clientVersion: client.version ?? null, botVersion: bot.version ?? null,
    compressionThreshold: compressionThreshold(), params: clone(submission),
    preEncoded: packetEvidence(serializer.createPacketBuffer({ name: 'custom_click_action', params: submission })),
    actualEncoded: [], actualFramed: [], droppedEncoded: 0, droppedFramed: 0
  }
  report.diagnostics ??= {}
  report.diagnostics.nativeDialogSubmission = details
  const capture = (key, dropped) => bytes => {
    if (details[key].length >= 64) { details[dropped]++; return }
    details[key].push({ ...packetEvidence(bytes), compressionThreshold: compressionThreshold(),
      state: client.state ?? null, clientVersion: client.version ?? null })
  }
  const encoded = capture('actualEncoded', 'droppedEncoded'), framed = capture('actualFramed', 'droppedFramed')
  serializer.on('data', encoded)
  framer.on('data', framed)
  return () => { serializer.removeListener('data', encoded); framer.removeListener('data', framed) }
}

function item (value, description = 'Item', allowEmpty = true) {
  assert.ok(record(value), description + ' lacks item evidence')
  if (empty(value)) { assert.ok(allowEmpty, description + ' is empty'); return value }
  assert.match(value.id || '', /^[a-z0-9_.-]+:[a-z0-9_./-]+$/, description + ' has an invalid id')
  assert.ok(Number.isInteger(value.count) && value.count > 0, description + ' has an invalid count')
  assert.ok(record(value.pdc), description + ' lacks complete PDC evidence')
  for (const key of ['serialized', 'unitSerialized']) {
    assert.ok(typeof value[key] === 'string' && value[key].length > 0 &&
      Buffer.from(value[key], 'base64').toString('base64') === value[key], description + ' lacks valid ' + key)
  }
  return value
}
function itemKey (value) {
  item(value, 'Component signature', false)
  // A serialized stack includes its count. The unit serialization retains every component without that count.
  const { count, serialized, uuid, world, position, ...components } = value
  return JSON.stringify(canonical(components))
}
function holdings (items) {
  const result = {}
  for (const value of items) {
    item(value)
    if (empty(value)) continue
    const key = itemKey(value)
    result[key] = (result[key] || 0) + value.count
  }
  return canonical(result)
}
function adjust (before, value, delta) {
  const after = clone(before), key = itemKey(value)
  const amount = (after[key] || 0) + delta
  assert.ok(Number.isInteger(amount) && amount >= 0, 'Item conservation would debit an absent component signature')
  if (amount) after[key] = amount
  else delete after[key]
  return canonical(after)
}
function canonicalItems (snapshot) {
  const values = snapshot.gameplay?.canonicalItems
  assert.ok(record(values), 'Missing canonical recovery item evidence')
  assert.deepEqual(Object.keys(values).sort(), [...NAMES].sort())
  for (const name of NAMES) {
    item(values[name], 'Canonical ' + name, false)
    assert.equal(values[name].id, RECOVERED[name])
    assert.equal(values[name].count, 1)
  }
  return values
}
function fixture (snapshot, name) {
  const value = snapshot.gameplay?.[name]
  assert.ok(record(value), 'Missing gameplay fixture: ' + name)
  assert.equal(typeof value.present, 'boolean')
  assert.equal(value.world, 'acceptance-world')
  assert.deepEqual(value.position, POSITIONS[name], name + ' moved outside its fixture location')
  if (!value.present) return value
  assert.equal(value.id, IDS[name])
  assert.match(value.uuid || '', /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i)
  assert.ok(Number.isInteger(value.hitbox) && value.hitbox >= 0, name + ' lacks a live network hitbox')
  assert.equal(value.blocked, false, name + ' is blocked by rejected persistent state')
  assert.ok(typeof value.variant === 'string' && value.variant.length > 0)
  assert.ok(typeof value.saved === 'string' && value.saved.length > 0, name + ' lacks saved controller NBT')
  item(value.source, name + ' source', false)
  const id = name === 'board' ? 'kaleidoscope_tavern:' + value.state?.style + '_sandwich_board' : IDS[name]
  assert.equal(value.source.id, id, name + ' has another source item')
  assert.equal(value.source.count, 1)
  const canonicalItem = canonicalItems(snapshot)[name]
  if (id === canonicalItem.id) assert.equal(itemKey(value.source), itemKey(canonicalItem), name + ' source components are not canonical')
  assert.ok(record(value.state), name + ' lacks controller state')
  return value
}
function durable (value) {
  const { hitbox, ...saved } = value
  return saved
}
function assertReloaded (actual, expected, name) {
  assert.equal(actual.present, true, name + ' did not reload')
  assert.deepEqual(durable(actual), durable(expected), name + ' changed across the normal server restart')
}
function looseItems (snapshot) {
  const values = snapshot.gameplay?.looseItems
  assert.ok(Array.isArray(values), 'Missing live world item evidence')
  const identities = new Set()
  for (const value of values) {
    item(value, 'World item', false)
    assert.match(value.uuid || '', /^[0-9a-f-]{36}$/i)
    assert.ok(!identities.has(value.uuid), 'A world item UUID was observed twice')
    identities.add(value.uuid)
    assert.equal(value.world, 'acceptance-world')
    assert.ok(Array.isArray(value.position) && value.position.length === 3 && value.position.every(Number.isFinite))
  }
  return values
}
function player (snapshot) {
  assert.match(snapshot.playerUuid || '', /^[0-9a-f-]{36}$/i)
  assert.ok(Array.isArray(snapshot.inventory) && snapshot.inventory.length === 36, 'Expected all 36 storage slots')
  snapshot.inventory.forEach((value, slot) => item(value, 'Inventory slot ' + slot))
  assert.deepEqual(snapshot.cursor, {}, 'A gameplay operation left an item on the inventory cursor')
  assert.equal(snapshot.view, 'CRAFTING', 'Gameplay must not use an inventory menu in place of the native Dialog')
  assert.ok(Array.isArray(snapshot.drops), 'Missing item-spawn receipt history')
  canonicalItems(snapshot)
  for (const name of NAMES) fixture(snapshot, name)
  looseItems(snapshot)
  return { uuid: snapshot.playerUuid, inventory: snapshot.inventory }
}
function worldKey (snapshot) {
  return {
    fixtures: Object.fromEntries(NAMES.map(name => [name, durable(fixture(snapshot, name))])),
    canonicalItems: canonicalItems(snapshot),
    loose: looseItems(snapshot).map(value => ({ uuid: value.uuid, key: itemKey(value), count: value.count }))
      .sort((a, b) => a.uuid.localeCompare(b.uuid)),
    drops: snapshot.drops
  }
}
function ledger (observations) {
  return Object.fromEntries(Object.entries(observations).map(([name, snapshot]) => [name, holdings(snapshot.inventory)]))
}
function assertLedger (observations, expected, expectedWorld = []) {
  assert.deepEqual(ledger(observations), expected, 'A player lost, gained, or changed an unexpected item component')
  for (const snapshot of Object.values(observations)) {
    assert.deepEqual(holdings(looseItems(snapshot)), holdings(expectedWorld), 'Unexpected, missing, or duplicated live world items')
  }
}
function assertDrops (observations, previous, rewards) {
  for (const snapshot of Object.values(observations)) {
    assert.deepEqual(snapshot.drops.slice(0, previous.length), previous, 'The item-spawn receipt history changed')
    const added = snapshot.drops.slice(previous.length)
    assert.deepEqual(added.map(value => ({ id: value.id, count: value.count })),
      rewards.map(value => ({ id: value.id, count: 1 })), 'The action spawned extra, missing, or incorrect items')
  }
}
function assertAbsent (observations) {
  for (const snapshot of Object.values(observations)) for (const name of NAMES) {
    assert.equal(fixture(snapshot, name).present, false, 'Recovered ' + name + ' reappeared')
  }
}
function assertCheckpointPlayers (observations, expected) {
  assert.deepEqual(Object.keys(observations).sort(), Object.keys(expected).sort(), 'The participating players changed')
  for (const [name, snapshot] of Object.entries(observations)) {
    assert.deepEqual(player(snapshot), expected[name], 'Offline player identity, inventory slots, or full components changed: ' + name)
  }
}
function assertSeed (seed, names) {
  assert.equal(seed.schemaVersion, 1)
  assert.equal(seed.phase, 'seed')
  assert.equal(seed.scenario, 'gameplay', 'Expected a gameplay seed checkpoint')
  assert.deepEqual(Object.keys(seed.players).sort(), [...names].sort())
  assert.deepEqual(Object.keys(seed.fixtures).sort(), [...NAMES].sort())
  const snapshot = { gameplay: { ...seed.fixtures, canonicalItems: seed.canonicalItems, looseItems: [] } }
  canonicalItems(snapshot)
  for (const name of NAMES) assert.equal(fixture(snapshot, name).present, true)
  assert.equal(new Set(NAMES.map(name => seed.fixtures[name].uuid)).size, 3)
  assert.deepEqual(seed.fixtures.chair.state, { carpet: 'red' })
  assert.equal(seed.fixtures.chair.variant, 'cushion/red')
  assert.deepEqual(seed.fixtures.board.state, FINAL_BOARD)
  assert.equal(seed.fixtures.board.variant, 'poppy')
  assert.equal(itemKey(seed.fixtures.board.source), itemKey(seed.canonicalItems.board), 'The patterned board source is not the canonical blank item')
  assert.deepEqual(seed.fixtures.molotov.state, { slots: [], ground: true })
  assert.equal(seed.fixtures.molotov.variant, 'ground')
  for (const name of ['chair', 'molotov']) {
    assert.equal(itemKey(seed.fixtures[name].source), itemKey(seed.canonicalItems[name]), 'The fixture source is not canonical: ' + name)
  }
  assert.equal(new Set(Object.values(seed.players).map(value => value.uuid)).size, names.length)
  const consumed = new Set([0, 2, 3, 4, 5])
  for (const [name, value] of Object.entries(seed.players)) {
    assert.match(value.uuid || '', /^[0-9a-f-]{36}$/i)
    assert.equal(value.inventory.length, 36)
    value.inventory.forEach((entry, slot) => {
      item(entry)
      const stock = name === names[0] ? STOCK[slot] : undefined
      if (!stock) assert.deepEqual(entry, {}, 'Unexpected seeded storage item: ' + name + ':' + slot)
      else {
        assert.equal(entry.id, stock[0], 'Seed material identity changed')
        assert.equal(entry.count, stock[1] - Number(consumed.has(slot)), 'Seed material consumption changed')
        assert.deepEqual(entry.pdc, {}, 'Seed materials must remain unmarked vanilla items')
      }
    })
  }
}
function readVerified (file, seedRaw, seed, names = Object.keys(seed.players)) {
  const value = JSON.parse(fs.readFileSync(file, 'utf8'))
  assert.equal(value.schemaVersion, 1)
  assert.equal(value.phase, 'verify')
  assert.equal(value.scenario, 'gameplay')
  assert.equal(value.seedStateSha256, sha256(seedRaw), 'The recovery checkpoint belongs to another seed')
  assert.deepEqual(Object.keys(value.players).sort(), Object.keys(seed.players).sort())
  assert.deepEqual(Object.keys(value.fixtures).sort(), [...NAMES].sort())
  for (const name of NAMES) {
    assert.deepEqual(value.fixtures[name], { present: false, world: 'acceptance-world', position: POSITIONS[name] })
  }
  const expected = Object.fromEntries(names.map(name => [name, holdings(seed.players[name].inventory)]))
  expected[names[0]] = adjust(expected[names[0]], seed.players[names[0]].inventory[0], 1)
  for (const name of ['chair', 'board']) expected[names[0]] = adjust(expected[names[0]], seed.canonicalItems[name], 1)
  expected[names[1]] = adjust(expected[names[1]], seed.canonicalItems.molotov, 1)
  for (const name of names) {
    assert.equal(value.players[name].uuid, seed.players[name].uuid)
    assert.ok(Array.isArray(value.players[name].inventory) && value.players[name].inventory.length === 36)
    assert.deepEqual(holdings(value.players[name].inventory), expected[name], 'Recovery checkpoint violates per-player component conservation: ' + name)
  }
  return value
}
function recordFixture (out, snapshot) {
  const file = path.join(out, 'fixture.json'), value = JSON.parse(fs.readFileSync(file, 'utf8'))
  value.gameplay = Object.fromEntries(NAMES.map(name => {
    const { present, uuid, id, world, position } = fixture(snapshot, name)
    return [name, { present, ...(present ? { uuid, id } : {}), world, position }]
  }))
  fs.writeFileSync(file, JSON.stringify(value, null, 2))
}

function unwrapNbt (tag) {
  assert.ok(record(tag) && typeof tag.type === 'string' && Object.hasOwn(tag, 'value'), 'Malformed typed Dialog NBT')
  if (tag.type === 'compound') {
    assert.ok(record(tag.value), 'Malformed Dialog compound')
    return Object.fromEntries(Object.entries(tag.value).map(([key, value]) => [key, unwrapNbt(value)]))
  }
  if (tag.type === 'list') {
    assert.ok(record(tag.value) && Array.isArray(tag.value.value) && typeof tag.value.type === 'string', 'Malformed Dialog list')
    return tag.value.value.map(value => unwrapNbt({ type: tag.value.type, value }))
  }
  return tag.value
}
function submissionFor (packet, text = TEXT, alignment = 'RIGHT') {
  assert.ok(record(packet?.dialog) && Object.hasOwn(packet.dialog, 'data'), 'Expected an inline server show_dialog packet')
  const root = packet.dialog.data, plain = unwrapNbt(root)
  assert.ok(record(plain) && Array.isArray(plain.inputs), 'The native Dialog has no inputs')
  assert.deepEqual(plain.inputs.map(value => value.key).sort(), ['alignment', 'text'])
  const input = plain.inputs.find(value => value.key === 'text')
  assert.ok(['text', 'minecraft:text'].includes(input.type), 'The text input has another native type')
  assert.equal(input.max_length, 320)
  assert.equal(input.multiline?.height, 120)
  assert.ok(typeof text === 'string' && text.length <= input.max_length)
  const choices = plain.inputs.find(value => value.key === 'alignment')
  assert.ok(['single_option', 'minecraft:single_option'].includes(choices.type), 'The alignment input is not single_option')
  assert.ok(Array.isArray(choices.options), 'Missing native alignment options')
  assert.deepEqual(choices.options.map(value => typeof value === 'string' ? value : value.id).sort(), ['CENTER', 'LEFT', 'RIGHT'])
  assert.equal(alignment, 'RIGHT')
  const actions = []
  const walk = tag => {
    if (tag.type === 'compound') {
      if (['dynamic/custom', 'minecraft:dynamic/custom'].includes(tag.value.type?.value)) actions.push(tag)
      for (const value of Object.values(tag.value)) walk(value)
    } else if (tag.type === 'list') for (const value of tag.value.value) walk({ type: tag.value.type, value })
  }
  walk(root)
  assert.equal(actions.length, 1, 'Expected exactly one native save callback action')
  const action = actions[0].value
  assert.equal(action.id?.type, 'string')
  assert.equal(action.id.value, 'paper:dialog_click_callback')
  assert.equal(action.additions?.type, 'compound', 'Missing native save callback additions')
  const additions = action.additions.value, callback = additions.id
  assert.equal(callback?.type, 'intArray', 'The callback UUID must remain its original NBT UUID tag')
  assert.ok(Array.isArray(callback.value) && callback.value.length === 4 && callback.value.every(value =>
    Number.isInteger(value) && value >= -2147483648 && value <= 2147483647), 'Invalid native callback UUID')
  assert.ok(!Object.hasOwn(additions, 'text') && !Object.hasOwn(additions, 'alignment'), 'The callback additions shadow a Dialog input')
  return { id: action.id.value, nbt: { type: 'compound', value: {
    ...clone(additions), text: { type: 'string', value: text }, alignment: { type: 'string', value: alignment }
  } } }
}

async function breakWithCombat ({ bot, name, live, playerUuid, read, until, delay, Vec3, evidence }) {
  let history = [], metaEntityId, hitTimes, hitboxes, previousHit
  const receipts = async () => {
    const value = await read()
    assert.equal(value.playerUuid, playerUuid, 'Combat snapshot actor UUID changed')
    assert.ok(Array.isArray(value.combat), 'Missing native furniture combat receipts')
    for (const [index, event] of value.combat.entries())
      assert.equal(event.sequence, index + 1, 'Combat receipt sequence is missing, repeated or reordered')
    assert.ok(value.combat.length >= history.length, 'Combat receipt history was truncated')
    assert.deepEqual(value.combat.slice(0, history.length), history, 'Combat receipt history changed')
    return clone(value.combat)
  }
  const validate = (event, kind) => {
    assert.equal(event.kind, kind, 'Unexpected native furniture combat event')
    assert.equal(event.player, bot.username, 'Combat receipt belongs to another actor')
    assert.equal(event.playerUuid, playerUuid, 'Combat receipt actor UUID differs')
    assert.equal(event.fixture, name, 'Combat receipt belongs to another fixture')
    assert.equal(event.uuid, live.uuid, 'Combat receipt furniture UUID differs')
    assert.equal(event.id, live.id, 'Combat receipt furniture identity differs')
    assert.deepEqual(event.position, live.position, 'Combat receipt furniture moved')
    assert.equal(event.cancelled, false, 'Native furniture ' + kind + ' was cancelled')
    assert.equal(event.regionOwned, true, 'Native furniture ' + kind + ' was outside its owning region')
    assert.ok(Number.isSafeInteger(event.metaEntityId) && event.metaEntityId >= 0, 'Invalid combat metadata entity')
    assert.ok(Array.isArray(event.hitboxes) && event.hitboxes.length > 0 &&
      event.hitboxes.every(value => Number.isSafeInteger(value) && value >= 0) &&
      new Set(event.hitboxes).size === event.hitboxes.length && event.hitboxes.includes(live.hitbox), 'Combat receipt lost the known live hitbox')
    assert.ok(Number.isSafeInteger(event.hitTimes) && event.hitTimes >= 1 && event.hitTimes <= 3,
      'Native furniture hit requirement exceeds the three-attack bound')
    assert.ok(Number.isSafeInteger(event.wallTimeMillis) && event.wallTimeMillis >= 0 &&
      Number.isSafeInteger(event.tick) && event.tick >= 0, 'Invalid native combat timestamp')
    for (const [key, length] of [['playerPosition', 3], ['playerRotation', 2]])
      assert.ok(Array.isArray(event[key]) && event[key].length === length && event[key].every(Number.isFinite), 'Invalid combat ' + key)
    if (metaEntityId !== undefined) {
      assert.equal(event.metaEntityId, metaEntityId, 'Native combat metadata entity changed')
      assert.equal(event.hitTimes, hitTimes, 'Native furniture hit requirement changed')
      assert.deepEqual(event.hitboxes, hitboxes, 'Native combat hitboxes changed')
    }
  }
  history = await receipts()
  const [x, y, z] = live.position
  await bot.lookAt(new Vec3(x, y + .45, z)); await delay(200)
  for (let hit = 1; hit <= 3; hit++) {
    const entity = await until(name + ' current network hitbox', () => bot.entities[live.hitbox])
    assert.equal(entity.id, live.hitbox, 'The native attack target is not the known live hitbox')
    bot.attack(entity)
    const current = await until(name + ' native hit receipt ' + hit, async () => {
      const value = await receipts(), added = value.slice(history.length)
      if (!added.length) return false
      assert.ok(added.length <= 2, 'A single attack produced multiple native combat receipts')
      validate(added[0], 'hit')
      if (added.length === 2) {
        assert.equal(hit, added[0].hitTimes, 'Furniture broke before its required native hit count')
        validate(added[1], 'break')
      }
      return value
    })
    const added = current.slice(history.length), received = added[0]
    metaEntityId ??= received.metaEntityId; hitTimes ??= received.hitTimes; hitboxes ??= clone(received.hitboxes)
    const intervalMillis = previousHit ? received.wallTimeMillis - previousHit.wallTimeMillis : null
    evidence.hits.push({ ...clone(received), intervalMillis })
    if (previousHit) assert.ok(intervalMillis >= 0 && intervalMillis <= 2000,
      'Native furniture consecutive hits exceeded the 2000ms reset window')
    if (previousHit) assert.ok(received.tick >= previousHit.tick, 'Native furniture hit tick moved backward')
    previousHit = received
    history = current
    if (hit < hitTimes) continue
    assert.equal(hit, hitTimes, 'Native furniture hit count exceeded its requirement')
    let broken = added[1]
    if (!broken) {
      const completed = await until(name + ' native break receipt', async () => {
        const value = await receipts(), pending = value.slice(history.length)
        if (!pending.length) return false
        assert.equal(pending.length, 1, 'Unexpected combat events while waiting for the native break')
        validate(pending[0], 'break')
        return value
      })
      broken = completed.at(-1); history = completed
    }
    validate(broken, 'break')
    assert.equal(broken.sequence, received.sequence + 1, 'Native break is not paired with its final hit')
    assert.ok(broken.wallTimeMillis >= received.wallTimeMillis && broken.tick >= received.tick,
      'Native break precedes its final hit')
    evidence.breaks.push(clone(broken))
    return
  }
  assert.fail(name + ' did not break after bounded attacks on its live hitbox')
}

async function gameplayScenario (h) {
  const { a, b, inspect, until, delay, Vec3, observeSnapshot, useAndObserve, selectHotbar, walkTo,
    report, out, phase, stateFile } = h
  assert.ok(stateFile && path.isAbsolute(stateFile), 'SENLUO_SCENARIO_STATE must be an absolute checkpoint path')
  assert.ok(['seed', 'verify', 'empty'].includes(phase))
  assert.equal(report.scenario, 'gameplay', 'Gameplay needs its own report identity')
  const players = [a, b], names = players.map(bot => bot.username), verifiedFile = stateFile + '.verified.json', identities = {}
  assert.equal(new Set(names).size, 2)
  const snapshot = bot => inspect(bot, 'gameinspect')
  const observe = (bot, description, ready, invariant) => observeSnapshot(bot, description, ready, snapshot, invariant)
  const readBoth = async () => {
    let previous
    return until('stable server inventories and gameplay world', async () => {
      const current = {}
      for (const bot of players) {
        current[bot.username] = await snapshot(bot)
        const value = player(current[bot.username])
        if (identities[bot.username]) assert.equal(value.uuid, identities[bot.username], 'A player UUID changed during the scenario')
        else identities[bot.username] = value.uuid
      }
      const key = Object.fromEntries(Object.entries(current).map(([name, value]) => [name, { player: player(value), world: worldKey(value) }]))
      const stable = previous && same(key, previous) && same(worldKey(current[a.username]), worldKey(current[b.username]))
      previous = key
      return stable ? current : false
    })
  }
  const use = async (bot, target) => {
    assert.equal(target.present, true, 'Cannot interact with an already removed fixture')
    const [x, y, z] = target.position
    await bot.lookAt(new Vec3(x, y + .45, z))
    await delay(200)
    bot._client.write('use_entity', { target: target.hitbox, mouse: 2, x: 0, y: .45, z: 0, hand: 0, sneaking: false })
  }
  const slot = (bot, selected) => selectHotbar(bot, selected, snapshot)
  const emptyHand = async bot => {
    const before = await snapshot(bot), selected = before.inventory.slice(0, 9).findIndex(empty)
    assert.ok(selected >= 0, 'A verified empty hotbar slot is required')
    const selectedState = await slot(bot, selected)
    assert.equal(selectedState.heldSlot, selected)
    assert.deepEqual(selectedState.inventory[selected], {}, 'The selected hand filled before the recovery')
    return selected
  }
  const unchangedFixtures = (after, before, except = []) => {
    for (const value of Object.values(after)) for (const name of NAMES.filter(name => !except.includes(name))) {
      assert.deepEqual(durable(fixture(value, name)), durable(fixture(before[a.username], name)), 'An unrelated fixture changed: ' + name)
    }
  }
  const parkPeer = async () => {
    await walkTo(b, new Vec3(b.entity.position.x, 200, 10.5), 'move peer into the clear recovery lane', .2)
    await walkTo(b, new Vec3(676.5, 200, 10.5), 'keep peer clear of all gameplay rewards', .2)
  }
  const clearRoute = async (x, z, description, bot = a) => {
    await walkTo(bot, new Vec3(bot.entity.position.x, 200, 10.5), description + ' enter clear lane', .2)
    await walkTo(bot, new Vec3(x, 200, 10.5), description + ' traverse clear lane', .2)
    await walkTo(bot, new Vec3(x, 200, z), description + ' approach', .25)
  }

  if (phase === 'seed') {
    assert.ok(!fs.existsSync(stateFile), 'Refusing to overwrite an existing seed checkpoint')
    assert.ok(!fs.existsSync(verifiedFile), 'Refusing to reuse an existing recovery checkpoint')
    await inspect(a, 'gameseed')
    await inspect(b, 'gamestock')
    let observations = await readBoth(), expected = ledger(observations)
    for (const bot of players) for (let index = 0; index < 36; index++) {
      const value = observations[bot.username].inventory[index], stock = bot === a ? STOCK[index] : undefined
      if (!stock) assert.deepEqual(value, {})
      else { assert.equal(value.id, stock[0]); assert.equal(value.count, stock[1]); assert.deepEqual(value.pdc, {}) }
    }
    const initial = observations[a.username]
    for (const name of NAMES) assert.equal(fixture(initial, name).present, true)
    assert.deepEqual(fixture(initial, 'chair').state, { carpet: 'empty' })
    assert.equal(fixture(initial, 'chair').variant, 'ground')
    assert.deepEqual(fixture(initial, 'board').state, DEFAULT_BOARD)
    assert.equal(fixture(initial, 'board').variant, 'base')
    assert.deepEqual(fixture(initial, 'molotov').state, { slots: [], ground: true })
    assert.equal(initial.gameCanceledTrades, 0)
    assert.equal(initial.gameCancelArmed, false)
    assertLedger(observations, expected)
    assertDrops(observations, [], [])
    await slot(a, 0)
    const armed = await inspect(a, 'gamecancel')
    assert.equal(armed.gameCancelArmed, true)
    await useAndObserve(a, () => use(a, fixture(armed, 'chair')), 'canceled chair cushion transaction received',
      value => value.gameCanceledTrades === 1 && !value.gameCancelArmed, snapshot,
      value => { assert.deepEqual(value.inventory, initial.inventory); assert.deepEqual(fixture(value, 'chair').state, { carpet: 'empty' }) })
    let after = await readBoth()
    assertCheckpointPlayers(after, Object.fromEntries(Object.entries(observations).map(([name, value]) => [name, player(value)])))
    assertLedger(after, expected); assertDrops(after, [], []); unchangedFixtures(after, observations)
    report.checks.gameplayCanceledCushion = { canceledTrades: 1, consumed: 0 }
    observations = after

    const consume = async (selected, name, expectedState, expectedVariant) => {
      const before = observations, value = before[a.username].inventory[selected]
      item(value, 'Consumed material', false)
      await slot(a, selected)
      await useAndObserve(a, () => use(a, fixture(before[a.username], name)), name + ' transaction committed',
        state => same(fixture(state, name).state, expectedState) && fixture(state, name).variant === expectedVariant, snapshot)
      expected[a.username] = adjust(expected[a.username], value, -1)
      after = await readBoth()
      assertLedger(after, expected); assertDrops(after, before[a.username].drops, []); unchangedFixtures(after, before, [name])
      const previousInventory = before[a.username].inventory, actualInventory = after[a.username].inventory
      for (let index = 0; index < 36; index++) {
        if (index !== selected) assert.deepEqual(actualInventory[index], previousInventory[index], 'The transaction changed another storage slot')
        else if (value.count === 1) assert.deepEqual(actualInventory[index], {})
        else { assert.equal(itemKey(actualInventory[index]), itemKey(value)); assert.equal(actualInventory[index].count, value.count - 1) }
      }
      assert.deepEqual(after[b.username].inventory, before[b.username].inventory)
      observations = after
    }
    await consume(0, 'chair', { carpet: 'red' }, 'cushion/red')
    await emptyHand(a)
    const dialogs = [], listener = packet => dialogs.push(packet)
    a._client.on('show_dialog', listener)
    let submission, stopTrace = () => {}
    try {
      await useAndObserve(a, () => use(a, fixture(observations[a.username], 'board')), 'native board Dialog opened', () => true, snapshot)
      const packet = await until('raw native show_dialog packet', () => dialogs[0])
      assert.equal(dialogs.length, 1, 'The interaction opened multiple Dialogs')
      submission = submissionFor(packet)
      const wireSubmission = nativeDialogSubmission(submission, a._client.serializer)
      stopTrace = traceDialogSubmission(a, wireSubmission, report)
      a._client.write('custom_click_action', wireSubmission)
      await observe(a, 'native Dialog text and RIGHT alignment saved', value =>
        fixture(value, 'board').state.text === TEXT && fixture(value, 'board').state.alignment === 'RIGHT')
    } finally { stopTrace(); a._client.removeListener('show_dialog', listener) }
    after = await readBoth()
    assertLedger(after, expected); assertDrops(after, [], []); unchangedFixtures(after, observations, ['board'])
    assert.deepEqual(after[a.username].inventory, observations[a.username].inventory)
    observations = after
    let boardState = { ...DEFAULT_BOARD, text: TEXT, alignment: 'RIGHT' }
    assert.deepEqual(fixture(after[a.username], 'board').state, boardState)
    boardState = { ...boardState, color: 'red' }; await consume(2, 'board', boardState, 'base')
    boardState = { ...boardState, glowing: true }; await consume(3, 'board', boardState, 'base')
    boardState = { ...boardState, waxed: true }; await consume(4, 'board', boardState, 'base')
    boardState = { ...boardState, style: 'poppy' }; await consume(5, 'board', boardState, 'poppy')
    const final = observations[a.username], seed = {
      schemaVersion: 1, phase: 'seed', scenario: 'gameplay',
      fixtures: Object.fromEntries(NAMES.map(name => [name, fixture(final, name)])), canonicalItems: canonicalItems(final),
      players: Object.fromEntries(Object.entries(observations).map(([name, value]) => [name, player(value)]))
    }
    assertSeed(seed, names)
    fs.writeFileSync(stateFile, JSON.stringify(seed, null, 2), { flag: 'wx' })
    recordFixture(out, final)
    report.checks.gameplaySeed = { fixtures: seed.fixtures, players: seed.players,
      nativeDialog: { packet: 'show_dialog', reply: 'custom_click_action', actionId: submission.id,
        callbackUuid: submission.nbt.value.id, text: TEXT, alignment: 'RIGHT' }, materialsConsumed: 5, looseItems: 0 }
    return
  }

  const seedRaw = fs.readFileSync(stateFile), seed = JSON.parse(seedRaw.toString('utf8'))
  assertSeed(seed, names)
  const verified = phase === 'empty' ? readVerified(verifiedFile, seedRaw, seed, names) : undefined
  if (phase === 'verify') assert.ok(!fs.existsSync(verifiedFile), 'Refusing to replay a completed recovery phase')
  let observations = await readBoth()
  for (const value of Object.values(observations)) assert.deepEqual(canonicalItems(value), seed.canonicalItems, 'Canonical item components changed across restart')
  if (phase === 'empty') {
    assertAbsent(observations); assertCheckpointPlayers(observations, verified.players)
    assertLedger(observations, ledger(observations)); assertDrops(observations, [], [])
    await delay(2000)
    const later = await readBoth()
    assertAbsent(later); assertCheckpointPlayers(later, verified.players)
    assertLedger(later, ledger(observations)); assertDrops(later, [], [])
    recordFixture(out, later[a.username])
    report.checks.gameplayEmpty = { fixtures: later[a.username].gameplay, players: verified.players, elapsedObservationMs: 2000 }
    return
  }
  for (const value of Object.values(observations)) for (const name of NAMES) assertReloaded(fixture(value, name), seed.fixtures[name], name)
  assertCheckpointPlayers(observations, seed.players)
  let expected = ledger(observations)
  assertLedger(observations, expected); assertDrops(observations, [], [])
  recordFixture(out, observations[a.username])
  // A owns the three world-drop recoveries. B witnesses those pickups before recovering the bottle directly.
  await parkPeer()

  const collectReward = async (before, reward, description) => {
    const previous = clone(expected), next = clone(expected)
    next[a.username] = adjust(previous[a.username], reward, 1)
    const progress = current => {
      assertDrops(current, before[a.username].drops, [reward])
      assert.deepEqual(current[b.username].inventory, before[b.username].inventory, 'The witness received or changed a recovery item')
      for (const value of Object.values(current)) {
        const loose = looseItems(value)
        assert.ok(loose.length <= 1, description + ' produced multiple live drops')
        for (const dropped of loose) { assert.equal(dropped.count, 1); assert.equal(itemKey(dropped), itemKey(reward), description + ' drop components differ from the canonical item') }
      }
      const carried = same(holdings(current[a.username].inventory), next[a.username])
      assert.ok(carried || same(holdings(current[a.username].inventory), previous[a.username]), description + ' changed unexpected player components')
      const loose = looseItems(current[a.username])
      assert.equal(loose.length + Number(carried), 1, description + ' was lost or duplicated between world and owner')
      return carried
    }
    let current = await readBoth()
    let carried = progress(current)
    if (!carried) {
      const worldItem = looseItems(current[a.username])[0]
      const target = await until(description + ' matching inventory or live network item', async () => {
        current = await readBoth()
        if (progress(current)) return { carried: true }
        const entity = Object.values(a.entities).find(value => value.uuid?.toLowerCase() === worldItem.uuid.toLowerCase())
        if (!entity) return false
        assert.ok(['item', 'Item', 'item_stack'].includes(entity.name), description + ' UUID belongs to another network entity')
        return { entity }
      })
      if (target.entity) await walkTo(a, () => target.entity.position, 'physically collect ' + description, .35,
        () => a.entities[target.entity.id] !== target.entity)
      current = await until(description + ' server-confirmed owner inventory', async () => {
        const value = await readBoth()
        return progress(value) ? value : false
      })
      carried = progress(current)
    }
    assert.equal(carried, true)
    assertLedger(current, next)
    expected = next
    return current
  }

  await slot(a, 1)
  let before = observations
  await useAndObserve(a, () => use(a, fixture(before[a.username], 'chair')), 'chair cushion sheared into a real world drop',
    value => fixture(value, 'chair').state.carpet === 'empty' && fixture(value, 'chair').variant === 'ground', snapshot)
  const carpet = { ...clone(seed.players[a.username].inventory[0]), count: 1 }
  assert.equal(carpet.id, 'minecraft:red_carpet')
  // Only the unit serialization is used for a different stack count; never present the old stack bytes as a new stack.
  carpet.serialized = carpet.unitSerialized
  observations = await collectReward(before, carpet, 'sheared red carpet')
  unchangedFixtures(observations, before, ['chair'])
  assert.deepEqual(observations[a.username].inventory[1], seed.players[a.username].inventory[1], 'Shearing unexpectedly damaged or replaced the shears')
  const recover = async name => {
    const original = fixture(observations[a.username], name), baseline = observations
    assert.equal(original.uuid, seed.fixtures[name].uuid)
    await emptyHand(a)
    const live = fixture(await snapshot(a), name)
    assert.equal(live.present, true, 'A removed fixture must never be attacked again')
    assert.equal(live.uuid, original.uuid)
    report.checks.gameplayRecovery ??= { hits: [], breaks: [] }
    // Full two-player stable snapshots can exceed CE's native 2000ms consecutive-hit reset window.
    // Between these at-most-three legal attacks, observe only the real native event receipts.
    await breakWithCombat({ bot: a, name, live, playerUuid: identities[a.username],
      read: () => inspect(a, 'gamecombat'), until, delay, Vec3, evidence: report.checks.gameplayRecovery })
    const current = await readBoth()
    for (const value of Object.values(current)) assert.equal(fixture(value, name).present, false,
      'A native break receipt did not remove its fixture')
    const reward = seed.canonicalItems[name]
    observations = await collectReward(baseline, reward, name + ' furniture')
    unchangedFixtures(observations, baseline, [name])
    for (const value of Object.values(observations)) assert.equal(fixture(value, name).present, false)
    return { uuid: original.uuid, id: reward.id, count: 1, pickedUpBy: a.username, item: reward }
  }
  const chair = await recover('chair')
  await clearRoute(682.5, 10.5, 'recover board')
  const board = await recover('board')
  await clearRoute(682.5, 6.5, 'recover ground molotov from the unobstructed east side', b)
  const held = await emptyHand(b)
  before = observations
  await useAndObserve(b, () => use(b, fixture(before[a.username], 'molotov')), 'ground molotov returned directly to a freshly empty hand',
    value => !fixture(value, 'molotov').present && value.inventory[held]?.id === IDS.molotov, snapshot)
  expected[b.username] = adjust(expected[b.username], seed.canonicalItems.molotov, 1)
  observations = await readBoth()
  assertAbsent(observations); assertLedger(observations, expected); assertDrops(observations, before[a.username].drops, [])
  assert.deepEqual(observations[b.username].inventory[held], seed.canonicalItems.molotov, 'Molotov delivery changed canonical source components')
  unchangedFixtures(observations, before, ['molotov'])
  await delay(2000)
  const later = await readBoth()
  assertAbsent(later); assertLedger(later, expected); assertDrops(later, observations[a.username].drops, [])
  assertCheckpointPlayers(later, Object.fromEntries(Object.entries(observations).map(([name, value]) => [name, player(value)])))
  const recovered = {
    schemaVersion: 1, phase: 'verify', scenario: 'gameplay', seedStateSha256: sha256(seedRaw),
    fixtures: Object.fromEntries(NAMES.map(name => [name, fixture(later[a.username], name)])),
    players: Object.fromEntries(Object.entries(later).map(([name, value]) => [name, player(value)]))
  }
  fs.writeFileSync(verifiedFile, JSON.stringify(recovered, null, 2), { flag: 'wx' })
  recordFixture(out, later[a.username])
  report.checks.gameplayVerify = { reloaded: seed.fixtures, shearedCarpets: 1, chair, board,
    molotov: { count: 1, pickedUpBy: b.username, item: seed.canonicalItems.molotov, worldDrops: 0 },
    recoveryStateSha256: sha256(fs.readFileSync(verifiedFile)), players: recovered.players, elapsedObservationMs: 2000 }
}

module.exports = gameplayScenario
module.exports.contract = { NAMES, IDS, RECOVERED, POSITIONS, TEXT, DEFAULT_BOARD, FINAL_BOARD, STOCK,
  item, itemKey, holdings, adjust, fixture, durable, assertReloaded, canonicalItems, looseItems, player,
  ledger, assertLedger, assertDrops, assertAbsent, assertCheckpointPlayers, assertSeed, readVerified,
  unwrapNbt, submissionFor, CUSTOM_PACKETS_12111, nativeDialogSubmission, packetEvidence, traceDialogSubmission, sha256 }
