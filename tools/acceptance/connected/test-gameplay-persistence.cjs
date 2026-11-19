'use strict'
// Contract tests only. No Java process, Minecraft server, Mineflayer, or external package is loaded.
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { EventEmitter } = require('node:events')
const scenario = require('./gameplay-persistence.cjs')
const c = scenario.contract
const clone = value => structuredClone(value)
const b64 = value => Buffer.from(JSON.stringify(value)).toString('base64')
const UUID = n => '00000000-0000-0000-0000-' + String(n).padStart(12, '0')
const A = 'SenLuoProbeA', B = 'SenLuoProbeB'
function evidence (id, count = 1, pdc = {}) {
  return { id, count, pdc: clone(pdc), serialized: b64({ id, count, pdc }), unitSerialized: b64({ id, count: 1, pdc }) }
}
function nbt (value) {
  if (Array.isArray(value)) {
    const values = value.map(nbt)
    return { type: 'list', value: { type: values[0]?.type || 'end', value: values.map(value => value.value) } }
  }
  if (value && typeof value === 'object') return { type: 'compound', value: Object.fromEntries(Object.entries(value).map(([key, value]) => [key, nbt(value)])) }
  return { type: typeof value === 'number' ? 'int' : typeof value === 'boolean' ? 'byte' : 'string', value }
}
function dialog () {
  const result = { dialog: { data: nbt({ type: 'minecraft:confirmation',
    inputs: [{ type: 'minecraft:text', key: 'text', max_length: 320, multiline: { height: 120 } },
      { type: 'minecraft:single_option', key: 'alignment', options: [{ id: 'LEFT', display: '左对齐' }, { id: 'CENTER', display: '居中' }, { id: 'RIGHT', display: '右对齐' }] }],
    yes: { label: '保存', action: { type: 'minecraft:dynamic/custom', id: 'paper:dialog_click_callback', additions: { id: 'replaced-below', marker: 'preserve me' } } },
    no: { label: '取消' }
  }) } }
  result.dialog.data.value.yes.value.action.value.additions.value.id = { type: 'intArray', value: [-2147483648, -1, 0, 2147483647] }
  return result
}
const action = packet => packet.dialog.data.value.yes.value.action.value

function fixture (t, options = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'senluo-gameplay-contract-'))
  const out = path.join(root, 'out'); fs.mkdirSync(out)
  fs.writeFileSync(path.join(out, 'fixture.json'), JSON.stringify({ port: 25565, packSha256: 'contract-only' }))
  const stateFile = path.join(root, 'seed.json'), reads = [], actions = [], delays = [], attacks = {}, uses = []
  const canonicalItems = Object.fromEntries(c.NAMES.map(name => [name, evidence(c.RECOVERED[name], 1, { 'craftengine:id': { string: c.RECOVERED[name] } })]))
  const state = { fixtures: {}, inventory: { [A]: Array.from({ length: 36 }, () => ({})), [B]: Array.from({ length: 36 }, () => ({})) },
    held: { [A]: 0, [B]: 0 }, interactions: { [A]: 0, [B]: 0 }, loose: [], drops: [], canceled: 0, armed: false, nextDrop: 700,
    combat: [], wallTime: 100000, nativeHits: {}, lastHit: {}, nativeResets: 0, combatReads: 0, pendingBreak: null }
  const clientA = new EventEmitter(), clientB = new EventEmitter()
  class Vec3 { constructor (x, y, z) { Object.assign(this, { x, y, z }) } }
  const bots = [A, B].map((username, index) => ({ username, entity: { position: new Vec3(680.5 + index, 200, 9.5) }, entities: {},
    _client: index ? clientB : clientA,
    lookAt: async target => { actions.push('lookAt'); state.look = target },
    setQuickBarSlot: slot => { actions.push('hotbar:' + slot); state.held[username] = slot },
    setControlState: (key, value) => { actions.push(key + ':' + value) }
  }))
  const byName = Object.fromEntries(bots.map(bot => [bot.username, bot]))
  const sourceFor = (name, style) => {
    if (options.missingSource === name) return {}
    const id = name === 'board' ? 'kaleidoscope_tavern:' + style + '_sandwich_board' : c.IDS[name]
    return evidence(id, 1, { 'craftengine:id': { string: id } })
  }
  const refresh = name => {
    const value = state.fixtures[name]
    if (!value.present) return
    value.saved = JSON.stringify({ envelope: name + '-v1', payload: value.state })
    if (name === 'board') value.source = sourceFor(name, value.state.style)
  }
  const prepare = () => {
    for (const [index, name] of c.NAMES.entries()) {
      state.fixtures[name] = { present: true, uuid: UUID(100 + index), id: c.IDS[name], world: 'acceptance-world',
        position: clone(c.POSITIONS[name]), variant: name === 'board' ? 'base' : 'ground', hitbox: 200 + index,
        source: sourceFor(name, 'base'), saved: '', blocked: false,
        state: name === 'chair' ? { carpet: 'empty' } : name === 'board' ? clone(c.DEFAULT_BOARD) : { slots: [], ground: true } }
      refresh(name)
      for (const bot of bots) bot.entities[200 + index] = { id: 200 + index, name, uuid: UUID(100 + index) }
    }
    state.loose = []; state.drops = []; state.armed = false; state.canceled = 0
  }
  const put = (name, reward) => {
    const inv = state.inventory[name]
    let slot = inv.findIndex(value => value.id && c.itemKey(value) === c.itemKey(reward))
    if (slot < 0) slot = inv.findIndex(value => !value.id)
    assert.ok(slot >= 0)
    const count = (inv[slot].count || 0) + reward.count
    inv[slot] = evidence(reward.id, count, reward.pdc)
    if (reward.unitSerialized !== evidence(reward.id, 1, reward.pdc).unitSerialized) {
      inv[slot].unitSerialized = reward.unitSerialized
      inv[slot].serialized = reward.count === 1 ? reward.serialized : b64({ modified: reward.unitSerialized, count })
    }
    return slot
  }
  const remove = name => {
    const value = state.fixtures[name]
    for (const bot of bots) delete bot.entities[value.hitbox]
    state.fixtures[name] = { present: false, world: 'acceptance-world', position: clone(c.POSITIONS[name]) }
  }
  const spawn = (name, reward) => {
    const bad = options.dropFault === 'components' ? { ...reward, unitSerialized: b64('damaged-components') } : reward
    const copies = options.dropFault === 'duplicateWorld' ? 2 : 1
    for (let i = 0; i < copies; i++) {
      const id = state.nextDrop++, value = { ...clone(bad), uuid: UUID(id), world: 'acceptance-world', position: clone(c.POSITIONS[name]) }
      state.loose.push(value)
      state.drops.push({ id: reward.id, count: 1 })
      for (const bot of bots) bot.entities[id] = { id, uuid: value.uuid, name: 'item', position: new Vec3(...value.position) }
    }
    if (options.dropFault === 'duplicateReceipt') state.drops.push({ id: reward.id, count: 1 })
    if (options.dropFault === 'witnessPickup' || options.immediatePickup) {
      const owner = options.dropFault === 'witnessPickup' ? B : A
      for (const value of state.loose) { put(owner, value); for (const bot of bots) delete bot.entities[Number(value.uuid.slice(-12))] }
      state.loose = []
    }
  }
  const collect = bot => {
    for (let i = state.loose.length - 1; i >= 0; i--) {
      const value = state.loose[i]
      if (Math.hypot(bot.entity.position.x - value.position[0], bot.entity.position.z - value.position[2]) < .85) {
        put(bot.username, value)
        if (options.dropFault === 'duplicateInventory') put(bot.username, value)
        for (const other of bots) delete other.entities[Number(value.uuid.slice(-12))]
        state.loose.splice(i, 1)
      }
    }
  }
  const takeOne = (name, slot) => {
    const value = state.inventory[name][slot]
    state.inventory[name][slot] = value.count === 1 ? {} : evidence(value.id, value.count - 1, value.pdc)
  }
  for (const bot of bots) {
    bot._client.state = 'play'; bot._client.version = '1.21.11'
    bot._client.serializer = new EventEmitter()
    bot._client.framer = new EventEmitter()
    bot._client.compressor = { compressionThreshold: 256 }
    // A standard-library fixture, not a Minecraft serializer or a claim of protocol validity.
    bot._client.serializer.proto = { sizeOf: (value, type) => {
      assert.equal(type, 'anonOptionalNbt')
      return Buffer.byteLength(JSON.stringify(value))
    } }
    bot._client.serializer.createPacketBuffer = packet => Buffer.concat([Buffer.from([0x41]), Buffer.from(JSON.stringify(packet.params))])
    bot.attack = entity => {
      actions.push('attack:' + entity.name)
      assert.ok(['chair', 'board'].includes(entity.name))
      assert.equal(state.fixtures[entity.name].present, true, 'Never attack a stale hitbox')
      attacks[entity.name] = (attacks[entity.name] || 0) + 1
      const name = entity.name, value = state.fixtures[name], required = options.hitsRequired || 1
      if (state.lastHit[name] !== undefined) state.wallTime += options.attackGapMs || 0
      if (state.lastHit[name] !== undefined && state.wallTime - state.lastHit[name] > 2000) {
        state.nativeHits[name] = 0; state.nativeResets++
      }
      state.lastHit[name] = state.wallTime
      state.nativeHits[name] = (state.nativeHits[name] || 0) + 1
      const event = { sequence: state.combat.length + 1, kind: 'hit', player: bot.username,
        playerUuid: bot.username === A ? UUID(1) : UUID(2), fixture: name, uuid: value.uuid, id: value.id,
        metaEntityId: 5000 + c.NAMES.indexOf(name), hitboxes: [value.hitbox], hitTimes: required,
        wallTimeMillis: state.wallTime, tick: Math.floor(state.wallTime / 50), cancelled: false, regionOwned: true,
        playerPosition: [bot.entity.position.x, bot.entity.position.y, bot.entity.position.z],
        playerRotation: [120.3, 51.55], position: clone(value.position) }
      if (options.combatFault !== 'stale') state.combat.push(event)
      if (options.combatFault === 'multipleHits') state.combat.push({ ...clone(event), sequence: state.combat.length + 1 })
      if (options.combatFault === 'earlyBreak') state.combat.push({ ...clone(event), sequence: state.combat.length + 1, kind: 'break' })
      if (state.nativeHits[name] >= required) {
        const broken = { ...clone(event), sequence: state.combat.length + 1, kind: 'break' }
        if (options.combatFault === 'missingHit') state.combat.pop()
        if (options.combatFault !== 'missingBreak' && options.combatFault !== 'stale') {
          if (options.delayedBreak) state.pendingBreak = { event: broken, remainingReads: 1 }
          else state.combat.push(broken)
        }
        if (options.combatFault !== 'breakStillPresent') { remove(name); spawn(name, canonicalItems[name]) }
      }
    }
    bot._client.write = (packet, value) => {
      actions.push(packet)
      if (packet === 'custom_click_action') {
        assert.equal(bot.username, A)
        assert.deepEqual(value, c.nativeDialogSubmission(c.submissionFor(dialog()), bot._client.serializer))
        const encoded = bot._client.serializer.createPacketBuffer({ name: packet, params: value })
        bot._client.serializer.emit('data', encoded)
        bot._client.framer.emit('data', Buffer.concat([Buffer.from([encoded.length & 0x7f]), encoded]))
        state.fixtures.board.state.text = value.nbt.value.text.value
        state.fixtures.board.state.alignment = value.nbt.value.alignment.value
        refresh('board'); return
      }
      assert.equal(packet, 'use_entity')
      assert.equal(value.mouse, 2); assert.equal(value.hand, 0); assert.equal(value.sneaking, false)
      const name = c.NAMES.find(name => state.fixtures[name].present && state.fixtures[name].hitbox === value.target)
      assert.ok(name)
      assert.equal(bot.username, name === 'molotov' ? B : A)
      const selected = state.held[bot.username], held = state.inventory[bot.username][selected], target = state.fixtures[name]
      uses.push({ name, held: clone(held) }); state.interactions[bot.username]++
      if (name === 'chair') {
        if (state.armed) {
          state.armed = false; state.canceled++
          if (options.consumeCanceled) takeOne(A, selected)
          return
        }
        if (held.id === 'minecraft:red_carpet') { takeOne(A, selected); target.state.carpet = 'red'; target.variant = 'cushion/red' }
        else { assert.equal(held.id, 'minecraft:shears'); target.state.carpet = 'empty'; target.variant = 'ground'; spawn(name, evidence('minecraft:red_carpet')) }
        refresh(name)
      } else if (name === 'board') {
        if (!held.id) { bot._client.emit('show_dialog', dialog()); return }
        if (held.id === 'minecraft:red_dye') target.state.color = 'red'
        else if (held.id === 'minecraft:glow_ink_sac') target.state.glowing = true
        else if (held.id === 'minecraft:honeycomb') target.state.waxed = true
        else { assert.equal(held.id, 'minecraft:poppy'); assert.equal(target.state.waxed, true); target.state.style = 'poppy'; target.variant = 'poppy' }
        takeOne(A, selected); refresh(name)
      } else {
        assert.deepEqual(held, {}, 'Molotov must be recovered into a newly verified empty hand')
        remove(name); state.inventory[bot.username][selected] = clone(canonicalItems.molotov)
        if (options.molotovDuplicate) spawn(name, canonicalItems.molotov)
        if (options.molotovComponents) state.inventory[bot.username][selected].unitSerialized = b64('wrong-molotov-components')
      }
    }
  }
  const harness = {
    a: bots[0], b: bots[1], stateFile, out, phase: 'seed', report: { scenario: 'gameplay', checks: {} }, Vec3,
    inspect: async (bot, operation) => {
      reads.push(operation)
      if (operation === 'gamecombat') {
        state.wallTime += options.combatSnapshotMs || 50
        state.combatReads++
        if (state.pendingBreak && state.pendingBreak.remainingReads-- === 0) {
          state.combat.push(state.pendingBreak.event); state.pendingBreak = null
        }
        const combat = clone(state.combat)
        if (combat.length && options.alterCombat) options.alterCombat(combat)
        return { playerUuid: options.combatActorUuid || (bot.username === A ? UUID(1) : UUID(2)), combat }
      }
      if (options.forbidHeavyWhileHitting) for (const name of ['chair', 'board'])
        assert.ok(!state.fixtures[name]?.present || !state.nativeHits[name], 'A heavy snapshot interrupted the native consecutive hits')
      state.wallTime += options.fullSnapshotMs || 125
      if (operation === 'gameseed') {
        prepare(); state.inventory[A] = Array.from({ length: 36 }, (_, index) => c.STOCK[index] ? evidence(...c.STOCK[index]) : {})
      } else if (operation === 'gamestock') state.inventory[B] = Array.from({ length: 36 }, () => ({}))
      else if (operation === 'gamecancel') state.armed = true
      else assert.equal(operation, 'gameinspect', 'Restart verification must never arrange or reset state')
      return { playerUuid: bot.username === A ? UUID(1) : UUID(2), inventory: clone(state.inventory[bot.username]), cursor: {}, view: 'CRAFTING',
        heldSlot: state.held[bot.username], furnitureInteractions: state.interactions[bot.username],
        gameCanceledTrades: state.canceled, gameCancelArmed: state.armed, drops: clone(state.drops),
        gameplay: { ...clone(state.fixtures), canonicalItems: clone(canonicalItems), looseItems: clone(state.loose) } }
    },
    until: async (description, predicate) => {
      for (let i = 0; i < 12; i++) { const result = await predicate(); if (result) return result }
      assert.fail('Timed out waiting for ' + description)
    },
    delay: async ms => { delays.push(ms); state.wallTime += ms },
    walkTo: async (bot, target, description, distance, stopWhen = () => false) => {
      actions.push('walk:' + description)
      if (stopWhen()) return
      const position = typeof target === 'function' ? target() : target
      bot.entity.position = new Vec3(position.x, position.y, position.z)
      collect(bot)
    }
  }
  harness.observeSnapshot = async (bot, description, ready, read, invariant = () => {}) => harness.until(description, async () => {
    const value = await read(bot); invariant(value); return ready(value) ? value : false
  })
  harness.useAndObserve = async (bot, fn, description, ready, read, invariant) => {
    const before = await read(bot); await fn()
    return harness.observeSnapshot(bot, description, value => value.furnitureInteractions > before.furnitureInteractions && ready(value), read, invariant)
  }
  harness.selectHotbar = async (bot, selected, read) => {
    bot.setQuickBarSlot(selected)
    return harness.observeSnapshot(bot, 'slot selected', value => value.heldSlot === selected, read)
  }
  const restart = phase => {
    harness.phase = phase; state.drops = []; state.interactions[A] = state.interactions[B] = 0; state.canceled = 0; state.armed = false
    state.combat = []; state.nativeHits = {}; state.lastHit = {}; state.nativeResets = 0; state.combatReads = 0; state.pendingBreak = null
    for (const name of c.NAMES) if (state.fixtures[name]?.present) {
      const value = state.fixtures[name], old = value.hitbox; value.hitbox += 1000
      for (const bot of bots) { delete bot.entities[old]; bot.entities[value.hitbox] = { id: value.hitbox, name, uuid: value.uuid } }
    }
    actions.length = 0; reads.length = 0
  }
  t.after(() => {
    assert.equal(path.dirname(path.resolve(root)), path.resolve(os.tmpdir()))
    fs.rmSync(root, { recursive: true, force: true })
  })
  return { harness, state, canonicalItems, actions, reads, delays, attacks, uses, restart, stateFile,
    seed: () => JSON.parse(fs.readFileSync(stateFile, 'utf8')), digest: () => c.sha256(fs.readFileSync(stateFile)) }
}

test('native Dialog callback preserves the exact UUID tag, additions, Chinese, backslashes and angle brackets', () => {
  const packet = dialog(), before = clone(packet), result = c.submissionFor(packet)
  assert.deepEqual(packet, before)
  assert.deepEqual(result.nbt.value.id, action(packet).additions.value.id)
  assert.deepEqual(result.nbt.value.marker, { type: 'string', value: 'preserve me' })
  assert.equal(result.nbt.value.text.value, c.TEXT)
  assert.ok(c.TEXT.includes('中文') && c.TEXT.includes('\\') && c.TEXT.includes('<') && c.TEXT.includes('\n'))
  assert.equal(result.nbt.value.alignment.value, 'RIGHT')
})
test('native Dialog accepts the codec-supported bare-string option representation', () => {
  const packet = dialog()
  packet.dialog.data.value.inputs.value.value[1].options = nbt(['LEFT', 'CENTER', 'RIGHT'])
  assert.equal(c.submissionFor(packet).id, 'paper:dialog_click_callback')
})
test('native Dialog schema changes only the common custom action payload encoding', () => {
  assert.deepEqual(c.CUSTOM_PACKETS_12111, { '1.21': { types: {
    packet_common_custom_click_action: ['container', [
      { name: 'id', type: 'string' }, { name: 'payloadLength', type: 'varint' },
      { name: 'nbt', type: 'anonOptionalNbt' }
    ]]
  } } })
})
test('native Dialog submission computes its length using the live NBT codec and preserves typed additions', () => {
  const value = c.submissionFor(dialog()), before = clone(value), calls = []
  const result = c.nativeDialogSubmission(value, { proto: { sizeOf: (tag, type) => { calls.push([clone(tag), type]); return 97 } } })
  assert.deepEqual(calls, [[value.nbt, 'anonOptionalNbt']])
  assert.deepEqual(result, { ...value, payloadLength: 97 })
  result.nbt.value.id.value[0] = 123
  assert.deepEqual(value, before)
})
for (const bytes of [0, -1, 1.5, NaN, 65537]) test('native Dialog refuses invalid or oversized encoded NBT length ' + bytes, () => {
  assert.throws(() => c.nativeDialogSubmission(c.submissionFor(dialog()), { proto: { sizeOf: () => bytes } }), /protocol byte limit/)
})
test('native Dialog accepts the native 65536 byte boundary', () => {
  assert.equal(c.nativeDialogSubmission(c.submissionFor(dialog()), { proto: { sizeOf: () => 65536 } }).payloadLength, 65536)
})
test('Dialog diagnostics retain raw bytes including an anomalous packet header and detach both listeners', () => {
  const serializer = new EventEmitter(), framer = new EventEmitter(), expected = Buffer.from([0x41, 0x01, 0x0a])
  const submission = { id: 'paper:dialog_click_callback', payloadLength: 1, nbt: { type: 'end', value: undefined } }
  serializer.createPacketBuffer = packet => { assert.deepEqual(packet, { name: 'custom_click_action', params: submission }); return expected }
  const bot = { version: '1.21.11', _client: { state: 'play', version: '1.21.11', serializer, framer,
    compressor: { compressionThreshold: 256 } } }, report = { checks: {} }
  const stop = c.traceDialogSubmission(bot, submission, report)
  serializer.emit('data', Buffer.from([0x0b, 0x00])); framer.emit('data', Buffer.from([0x03, 0x00, 0x0b, 0x00]))
  const result = report.diagnostics.nativeDialogSubmission
  assert.deepEqual(result.params, submission)
  assert.equal(result.preEncoded.base64, expected.toString('base64'))
  assert.equal(result.preEncoded.sha256, c.sha256(expected))
  assert.equal(result.preEncoded.firstVarInt, 65)
  assert.equal(result.actualEncoded[0].firstVarInt, 11)
  assert.equal(result.actualFramed[0].base64, 'AwALAA==')
  assert.equal(result.actualFramed[0].compressionThreshold, 256)
  assert.deepEqual(report.checks, {})
  stop(); assert.equal(serializer.listenerCount('data'), 0); assert.equal(framer.listenerCount('data'), 0)
  serializer.emit('data', expected); assert.equal(result.actualEncoded.length, 1)
})
test('Dialog diagnostics cap unrelated traffic without losing the raw submission bytes', () => {
  const serializer = new EventEmitter(), framer = new EventEmitter()
  serializer.createPacketBuffer = () => Buffer.from([65])
  const report = {}, stop = c.traceDialogSubmission({ _client: { serializer, framer } }, {}, report)
  for (let i = 0; i < 70; i++) { serializer.emit('data', Buffer.from([i])); framer.emit('data', Buffer.from([i])) }
  const result = report.diagnostics.nativeDialogSubmission
  assert.equal(result.actualEncoded.length, 64); assert.equal(result.actualFramed.length, 64)
  assert.equal(result.droppedEncoded, 6); assert.equal(result.droppedFramed, 6)
  assert.equal(result.preEncoded.base64, 'QQ=='); stop()
})
test('Dialog packet diagnostics report incomplete VarInts without throwing', () => {
  assert.match(c.packetEvidence(Buffer.from([0x80])).firstVarIntError, /VarInt/)
  assert.equal(c.packetEvidence(Buffer.from([0xac, 0x02])).firstVarInt, 300)
})
for (const [description, alter] of Object.entries({
  'registry holder instead of the observed inline Dialog': packet => { packet.dialog = { dialog: 4 } },
  'missing save action': packet => { delete packet.dialog.data.value.yes },
  'multiple save actions': packet => { packet.dialog.data.value.second = clone(packet.dialog.data.value.yes) },
  'callback UUID converted to text': packet => { action(packet).additions.value.id = nbt('00000000-0000-0000-0000-000000000001') },
  'missing callback UUID': packet => { delete action(packet).additions.value.id },
  'out of range UUID words': packet => { action(packet).additions.value.id.value[0] = 2147483648 },
  'wrong callback id': packet => { action(packet).id.value = 'other:callback' },
  'input-shadowing additions': packet => { action(packet).additions.value.text = nbt('shadow') },
  'wrong input name': packet => { packet.dialog.data.value.inputs.value.value[0].key.value = 'other' },
  'missing RIGHT option': packet => { packet.dialog.data.value.inputs.value.value[1].options.value.value.pop() },
  'wrong text capacity': packet => { packet.dialog.data.value.inputs.value.value[0].max_length.value = 100 },
  'wrong multiline height': packet => { packet.dialog.data.value.inputs.value.value[0].multiline.value.height.value = 1 },
  'malformed NBT list': packet => { packet.dialog.data.value.inputs.value.value = {} }
})) test('Dialog rejects ' + description, () => { const packet = dialog(); alter(packet); assert.throws(() => c.submissionFor(packet)) })

test('complete seed, verify and empty phases use real packet actions, live pickup and immutable checkpoints', async t => {
  const f = fixture(t)
  await scenario(f.harness)
  const seedDigest = f.digest(), seed = f.seed()
  assert.equal(seed.scenario, 'gameplay')
  assert.equal(seed.players[A].inventory[0].count, 2)
  assert.equal(seed.players[A].inventory[2].count, 2)
  assert.deepEqual(seed.fixtures.board.state, c.FINAL_BOARD)
  assert.ok(f.actions.includes('use_entity') && f.actions.includes('custom_click_action'))
  assert.equal(f.harness.a._client.listenerCount('show_dialog'), 0)
  assert.equal(f.harness.a._client.serializer.listenerCount('data'), 0)
  assert.equal(f.harness.a._client.framer.listenerCount('data'), 0)
  assert.equal(f.harness.report.diagnostics.nativeDialogSubmission.actualEncoded[0].firstVarInt, 65)
  f.restart('verify'); await scenario(f.harness)
  assert.equal(f.digest(), seedDigest)
  assert.ok(f.reads.every(value => ['gameinspect', 'gamecombat'].includes(value)))
  assert.equal(f.attacks.chair, 1); assert.equal(f.attacks.board, 1)
  assert.ok(f.actions.some(value => value.startsWith('walk:physically collect sheared red carpet')))
  const sidecar = fs.readFileSync(f.stateFile + '.verified.json'), recovered = JSON.parse(sidecar)
  assert.equal(recovered.seedStateSha256, seedDigest)
  assert.equal(recovered.phase, 'verify')
  assert.equal(recovered.players[A].inventory[0].count, 3)
  assert.equal(Object.values(f.state.fixtures).filter(value => value.present).length, 0)
  f.restart('empty'); await scenario(f.harness)
  assert.deepEqual(f.actions, [])
  assert.ok(f.reads.every(value => value === 'gameinspect'))
  assert.equal(f.digest(), seedDigest)
  assert.deepEqual(fs.readFileSync(f.stateFile + '.verified.json'), sidecar)
  assert.equal(f.harness.report.checks.gameplayEmpty.elapsedObservationMs, 2000)
})
test('recovery waits for each real native hit without heavy snapshots resetting the three-hit window', async t => {
  const f = fixture(t, { hitsRequired: 3, fullSnapshotMs: 1100, forbidHeavyWhileHitting: true })
  await scenario(f.harness); f.restart('verify'); await scenario(f.harness)
  assert.deepEqual(f.attacks, { chair: 3, board: 3 })
  assert.equal(f.state.nativeResets, 0)
  const evidence = f.harness.report.checks.gameplayRecovery
  assert.deepEqual(evidence.hits.map(value => value.sequence), [1, 2, 3, 5, 6, 7])
  assert.deepEqual(evidence.hits.map(value => value.intervalMillis), [null, 50, 50, null, 50, 50])
  assert.deepEqual(evidence.breaks.map(value => value.sequence), [4, 8])
  assert.ok(f.reads.includes('gamecombat'))
  assert.equal(f.harness.report.checks.gameplayVerify.chair.count, 1)
  assert.equal(f.harness.report.checks.gameplayVerify.board.count, 1)
})

test('the native fixture resets three attacks separated by the former four-snapshot stable observation', async t => {
  const f = fixture(t, { hitsRequired: 3, fullSnapshotMs: 1100 })
  await scenario(f.harness); f.restart('verify')
  for (let hit = 0; hit < 3; hit++) {
    f.harness.a.attack(f.harness.a.entities[f.state.fixtures.chair.hitbox])
    for (const bot of [f.harness.a, f.harness.b, f.harness.a, f.harness.b]) await f.harness.inspect(bot, 'gameinspect')
  }
  assert.equal(f.state.nativeHits.chair, 1)
  assert.equal(f.state.nativeResets, 2)
  assert.equal(f.state.fixtures.chair.present, true)
  assert.equal(f.state.combat.filter(value => value.kind === 'break').length, 0)
})

test('a final native break arriving on the next lightweight read is required before recovery accounting', async t => {
  const f = fixture(t, { hitsRequired: 3, delayedBreak: true, forbidHeavyWhileHitting: true })
  await scenario(f.harness); f.restart('verify'); await scenario(f.harness)
  assert.deepEqual(f.attacks, { chair: 3, board: 3 })
  assert.equal(f.state.combatReads, 10)
  assert.equal(f.harness.report.checks.gameplayRecovery.breaks.length, 2)
})

for (const [description, alterCombat] of Object.entries({
  'finally cancelled hit': values => { values[0].cancelled = true },
  'another actor': values => { values[0].player = B },
  'forged actor UUID': values => { values[0].playerUuid = UUID(2) },
  'another fixture': values => { values[0].fixture = 'board' },
  'another furniture UUID': values => { values[0].uuid = UUID(999) },
  'another furniture ID': values => { values[0].id = c.IDS.board },
  'another furniture position': values => { values[0].position[0]++ },
  'missing live hitbox': values => { values[0].hitboxes = [9999] },
  'invalid metadata entity': values => { values[0].metaEntityId = -1 },
  'unowned region': values => { values[0].regionOwned = false },
  'unsupported hit requirement': values => { values[0].hitTimes = 4 },
  'nonfinite event time': values => { values[0].wallTimeMillis = Infinity },
  'nonfinite player rotation': values => { values[0].playerRotation[0] = NaN },
  'missing sequence': values => { values[0].sequence = 2 },
  'break without its hit': values => { values[0].kind = 'break' }
})) test('native recovery rejects ' + description + ' after one attack without altering its checkpoint', async t => {
  const f = fixture(t, { hitsRequired: 3, alterCombat })
  await scenario(f.harness); f.restart('verify'); const digest = f.digest()
  await assert.rejects(scenario(f.harness))
  assert.deepEqual(f.attacks, { chair: 1 })
  assert.equal(f.digest(), digest)
  assert.ok(!fs.existsSync(f.stateFile + '.verified.json'))
})

for (const combatFault of ['multipleHits', 'earlyBreak', 'stale', 'missingHit', 'missingBreak', 'breakStillPresent']) {
  test('native recovery rejects ' + combatFault + ' and never resends an unconfirmed attack', async t => {
    const f = fixture(t, { combatFault, hitsRequired: ['multipleHits', 'earlyBreak', 'stale'].includes(combatFault) ? 3 : 1 })
    await scenario(f.harness); f.restart('verify'); const digest = f.digest()
    await assert.rejects(scenario(f.harness))
    assert.deepEqual(f.attacks, { chair: 1 })
    assert.equal(f.digest(), digest)
    assert.ok(!fs.existsSync(f.stateFile + '.verified.json'))
  })
}

test('old combat receipts cannot acknowledge a new attack', async t => {
  const f = fixture(t, { combatFault: 'stale', hitsRequired: 3 })
  await scenario(f.harness); f.restart('verify')
  f.state.combat.push({ sequence: 1, kind: 'hit', player: A })
  await assert.rejects(scenario(f.harness), /native hit receipt/)
  assert.deepEqual(f.attacks, { chair: 1 })
  assert.deepEqual(f.harness.report.checks.gameplayRecovery.hits, [])
})

test('a combat snapshot from another player UUID fails before sending an attack', async t => {
  const f = fixture(t, { combatActorUuid: UUID(2) })
  await scenario(f.harness); f.restart('verify')
  await assert.rejects(scenario(f.harness), /Combat snapshot actor UUID changed/)
  assert.deepEqual(f.attacks, {})
})

test('an actual hit interval exceeding the native 2000ms window fails without a third attack', async t => {
  const f = fixture(t, { hitsRequired: 3, attackGapMs: 2001 })
  await scenario(f.harness); f.restart('verify')
  await assert.rejects(scenario(f.harness), /2000ms reset window/)
  assert.deepEqual(f.attacks, { chair: 2 })
  assert.equal(f.state.nativeResets, 1)
  assert.equal(f.harness.report.checks.gameplayRecovery.hits[1].intervalMillis, 2051,
    'The rejected interval must remain available in the failure evidence')
  assert.ok(!fs.existsSync(f.stateFile + '.verified.json'))
})

test('the native inclusive 2000ms hit boundary remains accepted without resetting or adding attacks', async t => {
  const f = fixture(t, { hitsRequired: 3, attackGapMs: 1950 })
  await scenario(f.harness); f.restart('verify'); await scenario(f.harness)
  assert.deepEqual(f.attacks, { chair: 3, board: 3 })
  assert.equal(f.state.nativeResets, 0)
  assert.deepEqual(f.harness.report.checks.gameplayRecovery.hits.map(value => value.intervalMillis),
    [null, 2000, 2000, null, 2000, 2000])
})

for (const [description, alterCombat] of Object.entries({
  'rewritten history': values => { if (values.length >= 2) values[0].tick++ },
  'truncated history': values => { if (values.length >= 2) values.length = 0 },
  'changed metadata target': values => { if (values.length >= 2) values.at(-1).metaEntityId++ },
  'changed native hit requirement': values => { if (values.length >= 2) values.at(-1).hitTimes = 1 },
  'backward event tick': values => { if (values.length >= 2) values.at(-1).tick = values[0].tick - 1 },
  'backward event clock': values => { if (values.length >= 2) values.at(-1).wallTimeMillis = values[0].wallTimeMillis - 1 }
})) test('native recovery rejects ' + description + ' before a third attack', async t => {
  const f = fixture(t, { hitsRequired: 3, alterCombat })
  await scenario(f.harness); f.restart('verify')
  await assert.rejects(scenario(f.harness))
  assert.deepEqual(f.attacks, { chair: 2 })
  assert.ok(!fs.existsSync(f.stateFile + '.verified.json'))
})

for (const [description, alterCombat] of Object.entries({
  'finally cancelled break': values => { values.at(-1).cancelled = true },
  'break for another actor UUID': values => { values.at(-1).playerUuid = UUID(2) },
  'break for another metadata target': values => { values.at(-1).metaEntityId++ },
  'break preceding its final hit': values => { values.at(-1).wallTimeMillis = values[0].wallTimeMillis - 1 }
})) test('native recovery rejects ' + description + ' even when the fixture disappears', async t => {
  const f = fixture(t, { alterCombat })
  await scenario(f.harness); f.restart('verify')
  await assert.rejects(scenario(f.harness))
  assert.deepEqual(f.attacks, { chair: 1 })
  assert.equal(f.state.fixtures.chair.present, false)
  assert.ok(!fs.existsSync(f.stateFile + '.verified.json'))
})
test('an immediate legitimate actor pickup is still proven by component inventories and spawn receipts', async t => {
  const f = fixture(t, { immediatePickup: true })
  await scenario(f.harness); f.restart('verify'); await scenario(f.harness)
  assert.equal(f.state.loose.length, 0)
  assert.equal(f.harness.report.checks.gameplayVerify.shearedCarpets, 1)
})
test('seed refuses an existing checkpoint before arranging or changing anything', async t => {
  const f = fixture(t); fs.writeFileSync(f.stateFile, 'existing checkpoint')
  await assert.rejects(scenario(f.harness), /overwrite/)
  assert.deepEqual(f.reads, []); assert.deepEqual(f.actions, [])
  assert.equal(fs.readFileSync(f.stateFile, 'utf8'), 'existing checkpoint')
})
test('seed refuses an existing recovery sidecar before arranging anything', async t => {
  const f = fixture(t); fs.writeFileSync(f.stateFile + '.verified.json', '{}')
  await assert.rejects(scenario(f.harness), /recovery checkpoint/)
  assert.deepEqual(f.reads, []); assert.deepEqual(f.actions, [])
})
test('canceled cushion trade must leave every slot and all furniture unchanged', async t => {
  const f = fixture(t, { consumeCanceled: true })
  await assert.rejects(scenario(f.harness))
  assert.ok(!fs.existsSync(f.stateFile))
  assert.equal(f.actions.filter(value => value === 'use_entity').length, 1)
})
for (const name of c.NAMES) test('seed rejects a present ' + name + ' fixture without its original native source before player actions', async t => {
  const f = fixture(t, { missingSource: name })
  await assert.rejects(scenario(f.harness), /source is empty/)
  assert.deepEqual(f.actions, [])
  assert.ok(!fs.existsSync(f.stateFile))
})
for (const name of c.NAMES) test('a persisted seed cannot omit the original ' + name + ' source', async t => {
  const f = fixture(t); await scenario(f.harness)
  const value = f.seed(); value.fixtures[name].source = {}
  assert.throws(() => c.assertSeed(value, [A, B]), /source is empty/)
})
for (const name of c.NAMES) test('a persisted seed cannot authorize noncanonical ' + name + ' source components', async t => {
  const f = fixture(t); await scenario(f.harness)
  const value = f.seed(); value.fixtures[name].source.unitSerialized = b64('corrupted-original-source')
  assert.throws(() => c.assertSeed(value, [A, B]), /source components are not canonical/)
})

for (const [description, alter] of Object.entries({
  'inventory slot drift': f => { f.state.inventory[A][0].count++ },
  'inventory stack bytes drift': f => { f.state.inventory[A][0].serialized = b64('different-stack') },
  'inventory component drift': f => { f.state.inventory[A][0].unitSerialized = b64('different-component') },
  'witness inventory drift': f => { f.state.inventory[B][0] = evidence('minecraft:stone') },
  'fixture source drift': f => { f.state.fixtures.board.source.unitSerialized = b64('different-source') },
  'saved NBT drift': f => { f.state.fixtures.chair.saved += 'changed' },
  'controller state drift': f => { f.state.fixtures.board.state.text += 'changed' },
  'variant drift': f => { f.state.fixtures.chair.variant = 'ground' },
  'blocked persistence': f => { f.state.fixtures.molotov.blocked = true },
  'fixture UUID drift': f => { f.state.fixtures.chair.uuid = UUID(999) },
  'fixture position drift': f => { f.state.fixtures.chair.position[0]++ },
  'missing fixture': f => { f.state.fixtures.chair = { present: false, world: 'acceptance-world', position: clone(c.POSITIONS.chair) } },
  'canonical item component drift': f => { f.canonicalItems.molotov.unitSerialized = b64('changed-default') },
  'unexpected live item': f => { f.state.loose.push({ ...evidence('minecraft:stone'), uuid: UUID(500), world: 'acceptance-world', position: [680, 200, 8] }) }
})) test('verify rejects ' + description + ' before any protocol mutation', async t => {
  const f = fixture(t); await scenario(f.harness); f.restart('verify'); const digest = f.digest(); alter(f)
  await assert.rejects(scenario(f.harness))
  assert.deepEqual(f.actions, []); assert.equal(f.digest(), digest)
  assert.ok(f.reads.every(value => value === 'gameinspect'))
  assert.ok(!fs.existsSync(f.stateFile + '.verified.json'))
})
for (const fault of ['duplicateWorld', 'duplicateReceipt', 'components', 'witnessPickup', 'duplicateInventory']) {
  test('recovery rejects ' + fault + ' while preserving the seed checkpoint', async t => {
    const f = fixture(t, { dropFault: fault }); await scenario(f.harness); f.restart('verify'); const digest = f.digest()
    await assert.rejects(scenario(f.harness))
    assert.equal(f.digest(), digest)
    assert.ok(!fs.existsSync(f.stateFile + '.verified.json'))
  })
}
for (const fault of ['molotovDuplicate', 'molotovComponents']) {
  test('direct molotov delivery rejects ' + fault, async t => {
    const f = fixture(t, { [fault]: true }); await scenario(f.harness); f.restart('verify')
    await assert.rejects(scenario(f.harness))
    assert.ok(!fs.existsSync(f.stateFile + '.verified.json'))
  })
}
test('verify rejects the wrong checkpoint scenario before invoking the protocol', async t => {
  const f = fixture(t); await scenario(f.harness); f.restart('verify')
  const value = f.seed(); value.scenario = 'legacy'; fs.writeFileSync(f.stateFile, JSON.stringify(value))
  await assert.rejects(scenario(f.harness), /gameplay seed checkpoint/)
  assert.deepEqual(f.reads, []); assert.deepEqual(f.actions, [])
})
test('verify cannot replay or overwrite an already completed recovery', async t => {
  const f = fixture(t); await scenario(f.harness); f.restart('verify'); await scenario(f.harness)
  const before = fs.readFileSync(f.stateFile + '.verified.json'); f.restart('verify')
  await assert.rejects(scenario(f.harness), /completed recovery/)
  assert.deepEqual(f.actions, []); assert.deepEqual(f.reads, [])
  assert.deepEqual(fs.readFileSync(f.stateFile + '.verified.json'), before)
})
for (const [description, alter] of Object.entries({
  'revived chair': (f, seed) => { f.state.fixtures.chair = clone(seed.fixtures.chair) },
  'duplicate inventory item': f => { f.state.inventory[A][0].count++ },
  'changed owner inventory': f => { f.state.inventory[B][0] = evidence(c.RECOVERED.molotov) },
  'changed recovered components': f => { f.state.inventory[A].find(value => value.id === c.RECOVERED.board).unitSerialized = b64('bad-recovered-board') },
  'unexpected loose drop': f => { f.state.loose.push({ ...evidence(c.RECOVERED.chair), uuid: UUID(800), world: 'acceptance-world', position: [680, 200, 8] }) }
})) test('empty rejects ' + description + ' without repairing state', async t => {
  const f = fixture(t); await scenario(f.harness); const seed = f.seed(); f.restart('verify'); await scenario(f.harness)
  f.restart('empty'); alter(f, seed)
  await assert.rejects(scenario(f.harness)); assert.deepEqual(f.actions, [])
  assert.ok(f.reads.every(value => value === 'gameinspect'))
})
test('empty binds its immutable recovery checkpoint to the raw seed digest', async t => {
  const f = fixture(t); await scenario(f.harness); f.restart('verify'); await scenario(f.harness); f.restart('empty')
  fs.appendFileSync(f.stateFile, '\n')
  await assert.rejects(scenario(f.harness), /another seed/)
  assert.deepEqual(f.actions, []); assert.deepEqual(f.reads, [])
})
test('a forged sidecar cannot authorize extra items even when its seed digest is correct', async t => {
  const f = fixture(t); await scenario(f.harness); f.restart('verify'); await scenario(f.harness); f.restart('empty')
  const file = f.stateFile + '.verified.json', value = JSON.parse(fs.readFileSync(file, 'utf8'))
  value.players[A].inventory[0].count++
  fs.writeFileSync(file, JSON.stringify(value))
  await assert.rejects(scenario(f.harness), /component conservation/)
  assert.deepEqual(f.actions, []); assert.deepEqual(f.reads, [])
})
test('an invalid seed material profile fails before any restart observations or actions', async t => {
  const f = fixture(t); await scenario(f.harness); f.restart('verify')
  const value = f.seed(); value.players[A].inventory[0] = evidence('minecraft:stone', 2)
  fs.writeFileSync(f.stateFile, JSON.stringify(value))
  await assert.rejects(scenario(f.harness), /material identity/)
  assert.deepEqual(f.actions, []); assert.deepEqual(f.reads, [])
})
test('component accounting aggregates stack splits while retaining all unit bytes and PDC types', () => {
  const a = evidence('minecraft:red_carpet', 3, { 'acceptance:typed': { integer: 7 } })
  const one = evidence(a.id, 1, a.pdc), two = evidence(a.id, 2, a.pdc)
  assert.deepEqual(c.holdings([a]), c.holdings([one, two]))
  assert.deepEqual(c.adjust(c.holdings([a]), one, -1), c.holdings([two]))
  assert.notDeepEqual(c.holdings([a]), c.holdings([evidence(a.id, 3, { 'acceptance:typed': { string: '7' } })]))
  assert.throws(() => c.adjust({}, one, -1))
})
test('item evidence fails closed without complete count-independent component serialization', () => {
  const value = evidence('minecraft:stone')
  delete value.unitSerialized
  assert.throws(() => c.holdings([value]), /unitSerialized/)
  assert.throws(() => c.holdings([{ ...evidence('minecraft:stone'), serialized: 'not base64' }]), /serialized/)
})
