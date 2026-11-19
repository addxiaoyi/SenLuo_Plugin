'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { createHash } = require('node:crypto')
const { setTimeout: delay } = require('node:timers/promises')
const { promisify } = require('node:util')
const execFile = promisify(require('node:child_process').execFile)
const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')

const out = path.resolve(process.argv[2])
const { port, packSha256 } = JSON.parse(fs.readFileSync(path.join(out, 'fixture.json')))
const phase = process.env.SENLUO_SCENARIO_PHASE || 'single'
const scenario = process.env.SENLUO_SCENARIO_KIND || 'legacy'
const report = { passed: false, phase, scenario, protocol: '1.21.11', packs: {}, checks: {} }
const sessions = []
const shutdown = new AbortController()
const pendingPacks = new Set()
const pendingReaders = new Set()
let token = Math.floor(Math.random() * 80000000)
let networkError

async function until (description, predicate, timeout = 15000) {
  const end = Date.now() + timeout
  do {
    if (networkError) throw networkError
    const result = await predicate()
    if (result) return result
    await delay(50)
  } while (Date.now() < end)
  throw new Error(`Timed out waiting for ${description}`)
}
async function inspect (bot, action = 'inspect') {
  const id = ++token
  fs.appendFileSync(path.join(out, 'commands.txt'), `senluoprobe ${id} ${action} ${bot.username}\n`)
  const file = path.join(out, `reply-${id}.json`)
  await until('console ' + action, () => fs.existsSync(file))
  const response = JSON.parse(fs.readFileSync(file))
  assert.ok(!response.error, response.error)
  return response
}
async function observeSnapshot (bot, description, ready, read = inspect, invariant = () => {}, confirmations = 1) {
  let matched = 0
  return until(description, async () => {
    const snapshot = await read(bot)
    invariant(snapshot)
    matched = ready(snapshot) ? matched + 1 : 0
    return matched >= confirmations ? snapshot : false
  })
}
async function actAndObserve (bot, action, description, ready, read = inspect, invariant = () => {}, confirmations = 1) {
  await action()
  return observeSnapshot(bot, description, ready, read, invariant, confirmations)
}
async function useAndObserve (bot, action, description, ready, read = inspect, invariant = () => {}) {
  const before = await read(bot)
  assert.ok(Number.isInteger(before.furnitureInteractions) && before.furnitureInteractions >= 0,
    'Missing furniture interaction receipt counter')
  return actAndObserve(bot, action, description,
    snapshot => snapshot.furnitureInteractions > before.furnitureInteractions && ready(snapshot), read, invariant)
}
async function selectHotbar (bot, slot, read = inspect) {
  return actAndObserve(bot, () => bot.setQuickBarSlot(slot), 'held hotbar slot ' + slot,
    snapshot => snapshot.heldSlot === slot, read)
}
async function adjustDollAxes (bot, poseOf, read = inspect) {
  const expected = [1, 1, 1, 0, 0, 0, 0, 0, 0]
  const adjusted = [1.1, 1.1, 1.1, .05, .05, .05, 15, 15, 15]
  for (let axis = 0; axis < 9; axis++) {
    const previousWindow = bot.currentWindow.id
    expected[axis] = adjusted[axis]
    await actAndObserve(bot, () => click(bot, 18 + axis), 'doll axis ' + axis + ' committed', snapshot => {
      const pose = poseOf(snapshot)
      return typeof pose === 'string' && JSON.stringify(pose.split(',').map(Number)) === JSON.stringify(expected)
    }, read)
    await until('refreshed doll menu after axis ' + axis,
      () => bot.currentWindow?.inventoryStart === 36 && bot.currentWindow.id !== previousWindow)
  }
}
async function runReader (command, args) {
  const reading = execFile(command, args, {
    windowsHide: true, timeout: 65000, signal: shutdown.signal, killSignal: 'SIGKILL'
  })
  // Abort rejects execFile before the child necessarily emits close.
  const closed = new Promise(resolve => reading.child.once('close', () => {
    pendingReaders.delete(closed)
    resolve()
  }))
  pendingReaders.add(closed)
  await reading
}
async function receivePack (bot, username, offer) {
  const url = new URL(offer.url)
  assert.equal(url.hostname, '127.0.0.1'); assert.equal(url.protocol, 'http:')
  const status = result => bot._client.write('resource_pack_receive', { uuid: offer.uuid, result })
  status(3)
  const response = await fetch(url, { redirect: 'error', signal: AbortSignal.any([shutdown.signal, AbortSignal.timeout(45000)]), headers: {
    'User-Agent': 'Minecraft Java/1.21.11', 'X-Minecraft-Username': username,
    'X-Minecraft-UUID': bot._client.uuid, 'X-Minecraft-Version': '1.21.11',
    'X-Minecraft-Version-ID': '1.21.11', 'X-Minecraft-Pack-Format': '75', 'X-Minecraft-User-Agent': 'SenLuoAcceptance'
  } })
  shutdown.signal.throwIfAborted()
  assert.equal(response.status, 200, 'CE pack HTTP response')
  const bytes = Buffer.from(await response.arrayBuffer())
  assert.equal(createHash('sha1').update(bytes).digest('hex'), offer.hash)
  assert.equal(createHash('sha256').update(bytes).digest('hex'), packSha256)
  const archive = path.join(out, `${username}.zip`)
  fs.writeFileSync(archive, bytes)
  status(4)
  const readerReport = path.join(out, `${username}-resources.json`)
  const javaArgs = path.join(out, `${username}-java.args`)
  const inputs = ['-Xmx900M', '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8', '-cp', process.env.SENLUO_CLIENT_CLASSPATH,
    path.resolve(__dirname, '../VanillaPack12111.java'), readerReport, archive]
  assert.ok(inputs[4], 'Set SENLUO_CLIENT_CLASSPATH to the 1.21.11 client and libraries')
  fs.writeFileSync(javaArgs, inputs.map(s => '"'+s.replaceAll('\\','/').replaceAll('"','\\"')+'"').join('\n'))
  shutdown.signal.throwIfAborted()
  await runReader(process.env.SENLUO_JAVA || 'java', ['@'+javaArgs])
  const [decoded] = JSON.parse(fs.readFileSync(readerReport)); assert.equal(decoded.packSha256, packSha256)
  assert.equal(decoded.client,'1.21.11'); assert.ok(decoded.assetsRead > 0)
  status(0) // Pack contents loaded by the official headless reader; no GPU rendering.
  report.packs[username] = { bytes: bytes.length, sha1: offer.hash, ...decoded }
}
async function connect (username) {
  const customPackets = scenario === 'gameplay' ? require('./gameplay-persistence.cjs').contract.CUSTOM_PACKETS_12111 : undefined
  const bot = mineflayer.createBot({ host: '127.0.0.1', port, version: '1.21.11', username, auth: 'offline',
    customPackets, plugins: { resource_pack: false } })
  sessions.push(bot)
  bot.on('error', err => { networkError = err }); bot.on('kicked', msg => { networkError = new Error(`Kicked ${username}: ${JSON.stringify(msg)}`) })
  bot.once('end', reason => { networkError ??= new Error(`Disconnected ${username}: ${reason}`) })
  bot._client.on('add_resource_pack', offer => {
    if (shutdown.signal.aborted) return
    const pending = receivePack(bot, username, offer).catch(error => {
      if (!shutdown.signal.aborted) networkError = error
    }).finally(() => pendingPacks.delete(pending))
    pendingPacks.add(pending)
  })
  await until(username + ' spawn and pack', () => bot.entity && bot.entity.position.y !== 0 && report.packs[username], 90000)
  assert.equal((await inspect(bot, 'arrive')).arrived, true)
  await until('fixture chunks', () => bot.blockAt(new Vec3(679, 200, 7))?.name === 'smithing_table' && bot.entity.position.y > 198)
  return bot
}
const ns = 'kaleidoscopecookery:'
const count = (items, id) => items.filter(item => item.id === id).reduce((n, item) => n + item.count, 0)
async function walkTo (bot, target, description, stoppingDistance = .35, stopWhen = () => false) {
  const last = { description, stage: 'checking', position: null, target: null, distance: null, stoppingDistance }
  const updatePosition = () => {
    const position = bot.entity?.position
    last.position = position ? { x: position.x, y: position.y, z: position.z } : null
    last.distance = last.position && last.target
      ? Math.hypot(last.position.x - last.target.x, last.position.z - last.target.z) : null
  }
  const locate = () => {
    const position = typeof target === 'function' ? target() : target
    last.target = position ? { x: position.x, y: position.y, z: position.z } : null
    updatePosition()
    assert.ok(last.target && Object.values(last.target).every(Number.isFinite), 'Missing or nonfinite walking target')
    assert.ok(last.position && Object.values(last.position).every(Number.isFinite), 'Missing or nonfinite walking position')
    return last.target
  }
  try {
    try {
      assert.ok(Number.isFinite(stoppingDistance) && stoppingDistance > 0, 'Invalid walking stopping distance')
      await until(description, async () => {
        last.stage = 'checking'
        if (stopWhen()) return true
        const aimed = locate()
        if (last.distance <= stoppingDistance) return true
        bot.setControlState('forward', false)
        last.stage = 'turning'
        await bot.lookAt(new Vec3(aimed.x, last.position.y + 1.62, aimed.z), true)
        last.stage = 'rechecking'
        if (stopWhen()) return true
        const current = locate()
        if (last.distance <= stoppingDistance) return true
        // A live item may move while rotation is pending; face its new position on the next bounded attempt.
        if (current.x !== aimed.x || current.z !== aimed.z) return false
        last.stage = 'walking'
        bot.setControlState('forward', true)
        return false
      }, 5000)
    } finally { bot.setControlState('forward', false) }
    last.stage = 'settling'
    await delay(200)
  } catch (error) {
    // Keep the original error and stack while preserving the last physical state, including on timeouts.
    try {
      updatePosition()
      error.walkTo = { ...last }
      const diagnostic = 'walkTo=' + JSON.stringify(last)
      error.message += '; ' + diagnostic
      if (typeof error.stack === 'string' && !error.stack.includes(diagnostic)) error.stack += '\n' + diagnostic
    } catch {}
    throw error
  }
}
async function parkFurniturePeer (bot) {
  await walkTo(bot, new Vec3(bot.entity.position.x, bot.entity.position.y, 10.5), 'move peer to the clear recovery lane', .2)
  await walkTo(bot, new Vec3(676.5, 200, 10.5), 'keep peer clear of furniture drops', .2)
}
async function returnFurniturePeer (bot, position) {
  await walkTo(bot, new Vec3(position.x, position.y, 10.5), 'return peer through the clear recovery lane', .2)
  await walkTo(bot, position, 'return peer after furniture replay', .2)
}
async function pickUpFurniture (bot, id, removed, origin, description, read = inspect, participants = [bot]) {
  assert.equal(count(removed.drops, id), 1, description + ' must have exactly one drop')
  const expected = removed.drops.find(item => item.id === id)
  assert.ok(expected.pdc && Object.keys(expected.pdc).length, description + ' drop must retain its state PDC')
  assert.ok(Array.isArray(participants) && participants.includes(bot) && new Set(participants).size === participants.length,
    'Pickup observations must include each real participant exactly once')
  const carried = async () => {
    const observations = []
    for (const participant of participants) observations.push({ participant, snapshot: await read(participant) })
    const total = observations.reduce((sum, value) => sum + count(value.snapshot.inventory, id), 0)
    assert.ok(total <= 1, description + ' must exist exactly once across its participants')
    if (!total) return false
    const { participant, snapshot } = observations.find(value => count(value.snapshot.inventory, id))
    const slot = snapshot.inventory.findIndex(item => item.id === id)
    assert.deepEqual(snapshot.inventory[slot].pdc, expected.pdc, description + ' lost its state PDC')
    return { owner: participant.username, slot, snapshot }
  }
  let recovered = await until(description + ' inventory or network drop', async () => {
    const inventory = await carried()
    if (inventory) return inventory
    const drops = Object.values(bot.entities).filter(entity => {
      if (!['item', 'Item', 'item_stack'].includes(entity.name) ||
          !entity.metadata?.[bot.supportFeature('metadataIxOfItem')]) return false
      const item = entity.getDroppedItem()
      const custom = item?.components?.find(component => component.type === 'custom_data')?.data
      return custom?.value?.['craftengine:id']?.value === id &&
        Math.hypot(entity.position.x - origin.x, entity.position.z - origin.z) <= 4
    })
    assert.ok(drops.length <= 1, description + ' has multiple nearby network drops')
    return drops.length ? { entity: drops[0] } : false
  })
  if (recovered.entity) {
    // Furniture drops have randomized velocity; follow the network entity rather than a timed step.
    const droppedEntity = recovered.entity
    await walkTo(bot, () => droppedEntity.position, 'walk to ' + description, .35,
      () => bot.entities[droppedEntity.id] !== droppedEntity)
    // A removed network entity only stops walking. Exact server inventories must still prove the pickup.
    recovered = await until(description, carried)
  }
  assert.ok(recovered.slot < 9, 'Expected the recovered furniture in an empty hotbar slot')
  assert.equal(count(recovered.snapshot.inventory, id), 1)
  assert.deepEqual(recovered.snapshot.inventory[recovered.slot].pdc, expected.pdc, description + ' lost its state PDC')
  return recovered
}
async function open (bot, x, kind) {
  await bot.activateBlock(bot.blockAt(new Vec3(x,200,7)))
  await until(kind, () => bot.currentWindow?.type.endsWith(kind))
  // prismarine-windows 2.x describes the pre-template three-slot smithing table.
  // 1.21.11 sends template, base, addition and product before the player's inventory.
  const window = bot.currentWindow
  if (kind === 'smithing' && window.inventoryStart === 3) {
    window.inventoryStart = 4
    window.inventoryEnd = 40
    window.hotbarStart = 31
    window.craftingResultSlot = 3
  }
}
async function click (bot, slot, mode = 0, button = 0) {
  await bot.clickWindow(slot, button, mode)
  await delay(120)
}
async function move (bot, from, to) { await click(bot, from); await click(bot, to) }
function hotbar (bot, n) { return bot.currentWindow.inventoryStart + 27 + n }
async function smith (bot) {
  await open(bot,679,'smithing')
  await move(bot,hotbar(bot,0),0); await move(bot,hotbar(bot,1),1); await move(bot,hotbar(bot,3),2)
  const before = await observeSnapshot(bot, 'smithing result ready', value => value.top[3]?.id === ns+'netherite_kitchen_knife'); assert.equal(before.top[3].id, ns+'netherite_kitchen_knife')
  await click(bot,3,1)
  const blocked = await observeSnapshot(bot, 'canceled smithing trade', value => value.canceledSmith === 1, inspect, value => {
    assert.deepEqual(value.top, before.top); assert.deepEqual(value.inventory, before.inventory); assert.deepEqual(value.cursor, {})
  }, 2)
  assert.equal(blocked.canceledSmith,1); assert.deepEqual(blocked.top,before.top); assert.deepEqual(blocked.cursor,{})
  assert.deepEqual(blocked.inventory,before.inventory)
  await click(bot,3)
  const single = await observeSnapshot(bot, 'single smithing result delivered', value => value.cursor.id === ns+'netherite_kitchen_knife'); assert.equal(single.cursor.id, ns+'netherite_kitchen_knife'); assert.equal(single.top[0].count,2); assert.equal(single.top[2].count,4); assert.deepEqual(single.top[1],{})
  await click(bot,hotbar(bot,0)); await move(bot,hotbar(bot,2),1); await click(bot,3,1)
  const shifted = await observeSnapshot(bot, 'shift smithing result delivered', value => count(value.inventory,ns+'netherite_kitchen_knife') === 2); assert.equal(count(shifted.inventory,ns+'netherite_kitchen_knife'),2)
  assert.equal(shifted.top[0].count,1); assert.equal(shifted.top[2].count,3); assert.deepEqual(shifted.top[1],{}); assert.deepEqual(shifted.cursor,{})
  bot.closeWindow(bot.currentWindow)
  const closed = await observeSnapshot(bot, 'smithing inputs returned', value => value.view === 'CRAFTING' && count(value.inventory,'minecraft:netherite_ingot') === 3 && count(value.inventory,'minecraft:netherite_upgrade_smithing_template') === 1)
  assert.equal(count(closed.inventory,'minecraft:netherite_ingot'),3); assert.equal(count(closed.inventory,'minecraft:netherite_upgrade_smithing_template'),1)
  report.checks.smithing = { canceledShift: 1, productsWithPreservedComponents: 2, remainingTemplates: 1, remainingIngots: 3 }
}
async function steamer (bot) {
  await open(bot,681,'crafting')
  await move(bot,hotbar(bot,4),1); await click(bot,hotbar(bot,5))
  for (const slot of [2,3,4,5]) await click(bot,slot,0,1)
  const slots = Array(4).fill(ns+'raw_dough*1')
  const before = await observeSnapshot(bot, 'filled steamer recipe ready', value => JSON.stringify(value.top[0]?.slots) === JSON.stringify(slots))
  assert.deepEqual(before.top[0].slots,slots)
  await click(bot,0); await click(bot,hotbar(bot,4)); bot.closeWindow(bot.currentWindow)
  const crafted = await observeSnapshot(bot, 'crafted steamer in inventory', value => value.view === 'CRAFTING' && count(value.inventory,ns+'steamer') === 1); const basket = crafted.inventory.findIndex(item => item.id === ns+'steamer' && item.slots.join() === slots.join()); assert.ok(basket >= 0 && basket < 9)
  assert.equal(count(crafted.inventory,ns+'raw_dough'),0); await selectHotbar(bot, basket)
  const placed = await actAndObserve(bot, () => bot.activateBlock(bot.blockAt(new Vec3(680,199,8)),new Vec3(0,1,0),new Vec3(.5,1,.5)),
    'filled steamer placed', value => JSON.stringify(value.placedSlots.slice(0,4)) === JSON.stringify(slots) && count(value.inventory,ns+'steamer') === 0)
  assert.deepEqual(placed.placedSlots.slice(0,4),slots); assert.equal(count(placed.inventory,ns+'steamer'),0)
  const pos = new Vec3(680,200,8)
  const take = (description, ready) => actAndObserve(bot, () => bot.activateBlock(bot.blockAt(pos)), description, ready)
  await selectHotbar(bot, 6)
  const emptied = await take('steamer ingredients returned', value => value.placedSlots.length && value.placedSlots.every(slot => slot === 'empty') && count(value.inventory,ns+'raw_dough') === 4); assert.ok(emptied.placedSlots.length && emptied.placedSlots.every(slot => slot === 'empty'))
  assert.equal(count(emptied.inventory,ns+'raw_dough'),4)
  await selectHotbar(bot, 7)
  const recovered = await take('empty steamer recovered', value => value.placedSlots.length === 0 && count(value.inventory,ns+'steamer') === 1); assert.deepEqual(recovered.placedSlots,[])
  assert.equal(count(recovered.inventory,ns+'steamer'),1); assert.equal(count(recovered.inventory,ns+'raw_dough'),4)
  assert.ok(recovered.inventory.find(item => item.id === ns+'steamer').slots.every(slot => slot === 'empty'))
  assert.equal(count(recovered.drops,ns+'steamer') + count(recovered.drops,ns+'raw_dough'),0)
  report.checks.steamer = { craftedSlots: before.top[0].slots, placedSlots: placed.placedSlots.slice(0,4), recoveredDough: 4, recoveredSteamers: 1, extraDrops: 0 }
}
async function scarecrow (a,b) {
  const { scarecrowBodyId } = await inspect(a,'scarecrow')
  const center = new Vec3(680.5,200,7.5)
  const entity = bot => bot.entities[scarecrowBodyId]
  const targetA = await until('scarecrow A', () => entity(a)); const targetB = await until('scarecrow B', () => entity(b))
  // CE checks the ray against this hitbox using the server's last received rotation.
  // Wait for the normal look packet instead of Mineflayer's force option, which only updates local state.
  await Promise.all([a.lookAt(center.offset(0,.5,0)),b.lookAt(center.offset(0,.5,0))])
  await delay(200)
  b.attack(targetB)
  const protectedHit = await observeSnapshot(b, 'protected scarecrow hit recorded', value => value.hits.length === 1, inspect, value => {
    assert.equal(value.scarecrow, true); assert.deepEqual(value.drops, [])
  }, 2); assert.equal(protectedHit.scarecrow,true); assert.deepEqual(protectedHit.hits.map(h=>h.canceled),[true]); assert.deepEqual(protectedHit.drops,[])
  a.attack(targetA); await delay(85); b.attack(targetB)
  const final = await observeSnapshot(a, 'concurrent scarecrow removal committed', value => !value.scarecrow && value.hits.length === 3); assert.equal(final.scarecrow,false); assert.deepEqual(final.hits.map(h=>h.canceled),[true,true,false]); assert.deepEqual(final.hits.map(h=>h.player),[b.username,a.username,b.username]); assert.ok(final.hits.every(h=>h.regionOwned)); assert.ok(final.hits[2].tick-final.hits[1].tick<=5)
  assert.equal(count(final.drops,ns+'scarecrow'),1)
  report.checks.scarecrow = { hits: final.hits, drops: final.drops }
}
function boardTulipGain (players, values, inventories, returned) {
  if (returned) {
    assert.match(returned.id, /^minecraft:(orange|pink|red|white)_tulip$/)
    assert.equal(returned.count, 1, 'Board cleanup must collect only its one recorded flower')
  }
  let collected
  for (let player = 0; player < players.length; player++) {
    const normalized = values[player].inventory.map((item, slot) => {
      const old = inventories[player][slot]
      if (!returned || item.id !== returned.id || (old.id && old.id !== returned.id)) return item
      const gained = item.count - (old.count || 0)
      assert.ok(gained === 0 || gained === 1, 'Board cleanup must collect only its one recorded flower')
      if (!gained) return item
      assert.deepEqual({ ...item, count: 1 }, returned, 'Board cleanup changed the recorded flower item data')
      assert.equal(collected, undefined, 'Board cleanup duplicated the returned flower across slots or players')
      collected = { owner: players[player].username, slot }
      return old.id ? { ...item, count: item.count - 1 } : {}
    })
    assert.deepEqual(normalized, inventories[player], 'Board cleanup changed an unrelated inventory item')
  }
  return collected
}
async function harvestTulipConcurrently (a, b, oldHitbox, use, emptyState, read = inspect) {
  const players = [a, b]
  const before = await Promise.all(players.map(bot => read(bot)))
  for (const value of before) {
    assert.ok(Number.isInteger(value.furnitureInteractions) && value.furnitureInteractions >= 0,
      'Missing furniture interaction receipt counter')
    assert.equal(value.boardHitbox, oldHitbox, 'The concurrent requests must target the same original hitbox')
    assert.deepEqual(value.drops, [])
  }
  // Send each old-target packet once, without waiting for either controller invocation.
  // CE may replace the hitbox after the first harvest, so the second packet can target an expired ID.
  for (const bot of players) use(bot, oldHitbox)
  return until('single concurrent tulip harvest committed', async () => {
    const values = await Promise.all(players.map(bot => read(bot)))
    for (let index = 0; index < players.length; index++) {
      assert.ok(values[index].drops.length <= 1, 'Concurrent harvest produced multiple drops')
      if (values[index].drops.length) assert.equal(values[index].drops[0].count, 1, 'Concurrent harvest duplicated its returned tulip')
    }
    const returned = values.find(value => value.drops.length)?.drops[0]
    for (const value of values) if (value.drops.length) assert.deepEqual(value.drops, [returned])
    boardTulipGain(players, values, before.map(value => value.inventory), returned)
    const acknowledged = players.filter((bot, index) =>
      values[index].furnitureInteractions > before[index].furnitureInteractions).map(bot => bot.username)
    if (!acknowledged.length || !values.every(value => value.board === emptyState && value.drops.length === 1)) return false
    const newHitbox = values[0].boardHitbox
    if (!Number.isInteger(newHitbox) || !values.every(value => value.boardHitbox === newHitbox)) return false
    if (acknowledged.length < players.length && newHitbox === oldHitbox) return false
    return { harvested: values[0], observations: values, attemptedPlayers: players.map(bot => bot.username), acknowledged, oldHitbox, newHitbox }
  })
}
async function collectBoardTulip (a, b, baselines, finished, origin, read = inspect) {
  const players = [a, b], expected = finished.drops[0]
  assert.equal(finished.drops.length, 1)
  assert.equal(expected.count, 1)
  assert.match(expected.id, /^minecraft:(orange|pink|red|white)_tulip$/)
  const before = baselines.map(value => structuredClone(value.inventory))
  const base = structuredClone(before)
  const validate = (values, inventories = base) => {
    for (let player = 0; player < players.length; player++) {
      const value = values[player]
      assert.equal(value.board, finished.board); assert.equal(value.boardHitbox, finished.boardHitbox)
      assert.deepEqual(value.drops, finished.drops, 'Board cleanup must not create another drop')
      assert.deepEqual(value.top, baselines[player].top); assert.deepEqual(value.cursor, baselines[player].cursor)
    }
    return boardTulipGain(players, values, inventories, expected)
  }
  const observe = async inventories => {
    const values = await Promise.all(players.map(bot => read(bot)))
    return { values, collected: validate(values, inventories) }
  }
  let result = await observe()
  let movedStackToOffhand = false
  if (!result.collected) {
    // The full-inventory assertions are complete. Reserve one storage slot without spawning another item.
    assert.deepEqual(base[0][2], { id: 'minecraft:cobblestone', count: 64 })
    const sourceSlot = a.inventory.hotbarStart + 2, offhandSlot = a.getEquipmentDestSlot('off-hand')
    await until('board cleanup inventory source ready', () =>
      a.inventory.slots[sourceSlot]?.name === 'cobblestone' && a.inventory.slots[sourceSlot].count === 64 &&
      !a.inventory.slots[offhandSlot])
    await a.moveSlotItem(sourceSlot, offhandSlot)
    base[0][2] = {}
    result = await until('board cleanup storage slot reserved', async () => {
      const values = await Promise.all(players.map(bot => read(bot)))
      const moved = values[0].inventory[2].id !== 'minecraft:cobblestone'
      if (Object.keys(values[0].cursor).length) {
        assert.ok(moved, 'Board cleanup cannot hold the same stack in its source slot and cursor')
        assert.deepEqual(values[0].cursor, before[0][2], 'Board cleanup cursor must contain exactly the stack being moved')
        const inFlight = values.map((value, index) => index === 0 ? { ...value, cursor: baselines[0].cursor } : value)
        validate(inFlight)
        return false
      }
      const collected = validate(values, moved ? base : before)
      const offhand = a.inventory.slots[offhandSlot]
      return moved && offhand?.name === 'cobblestone' && offhand.count === 64 ? { values, collected } : false
    })
    movedStackToOffhand = true
    if (!result.collected) {
      const start = new Vec3(a.entity.position.x, a.entity.position.y, a.entity.position.z)
      const target = await until('board flower inventory or network drop', async () => {
        const observation = await observe()
        if (observation.collected) return observation
        const drops = Object.values(a.entities).filter(entity => {
          if (!['item', 'Item', 'item_stack'].includes(entity.name) ||
              !entity.metadata?.[a.supportFeature('metadataIxOfItem')]) return false
          const item = entity.getDroppedItem()
          return item?.name === expected.id.slice('minecraft:'.length) &&
            Math.hypot(entity.position.x - origin.x, entity.position.z - origin.z) <= 4
        })
        assert.ok(drops.length <= 1, 'Board cleanup found duplicate matching network flowers')
        return drops.length ? { entity: drops[0] } : false
      })
      if (target.entity) {
        await walkTo(a, () => target.entity.position, 'collect the recorded board flower', .35,
          () => a.entities[target.entity.id] !== target.entity)
        result = await until('recorded board flower in server inventory', async () => {
          const observation = await observe()
          return observation.collected ? observation : false
        })
        await walkTo(a, start, 'return after collecting the board flower')
      } else result = target
    }
    assert.equal(a.inventory.slots[offhandSlot]?.name, 'cobblestone')
    assert.equal(a.inventory.slots[offhandSlot]?.count, 64)
  }
  const final = await observe()
  assert.ok(final.collected, 'The recorded board flower must be held before the next fixture clears inventory')
  return { ...final.collected, id: expected.id, count: 1, movedStackToOffhand }
}
async function tulipBoard (a,b) {
  const state = n => 'kaleidoscope_deco/kaleidoscope_bar/blackboard/' + n
  const initial = await inspect(a, 'tulip')
  const otherInitial = await inspect(b, 'tulip')
  assert.equal(initial.board, state(0))
  const spot = new Vec3(682.5, 200.45, 9.5)
  await Promise.all([a.lookAt(spot), b.lookAt(spot)])
  const use = (bot, id) => bot._client.write('use_entity', {
    target: id, mouse: 2, x: 0, y: .45, z: 0, hand: 0, sneaking: false
  })
  await selectHotbar(a, 0); await selectHotbar(b, 1)
  const decorated = await useAndObserve(a, () => use(a, initial.boardHitbox), 'tulip decoration committed',
    value => value.board === state(9) && value.inventory[0]?.count === 1)
  assert.equal(decorated.board, state(9))
  assert.equal(decorated.inventory[0].count, 1)
  assert.ok(decorated.inventory.every(item => item.count > 0))
  assert.deepEqual(decorated.drops, [])
  await selectHotbar(a, 1)
  const protectedBoard = await useAndObserve(b, () => use(b, decorated.boardHitbox), 'board protection cancellation recorded',
    value => value.boardDenied === 1, inspect, value => {
      assert.equal(value.board, state(9)); assert.deepEqual(value.drops, [])
    })
  assert.equal(protectedBoard.boardDenied, 1); assert.equal(protectedBoard.flowerDenied, 0)
  assert.equal(protectedBoard.board, state(9)); assert.deepEqual(protectedBoard.drops, [])
  const blockedDrop = await useAndObserve(a, () => use(a, protectedBoard.boardHitbox), 'flower drop cancellation recorded',
    value => value.flowerDenied === 1, inspect, value => {
      assert.equal(value.board, state(9)); assert.deepEqual(value.inventory, decorated.inventory); assert.deepEqual(value.drops, [])
    })
  assert.equal(blockedDrop.flowerDenied, 1); assert.equal(blockedDrop.board, state(9))
  assert.deepEqual(blockedDrop.inventory, decorated.inventory); assert.deepEqual(blockedDrop.drops, [])
  const concurrent = await harvestTulipConcurrently(a, b, blockedDrop.boardHitbox, use, state(0))
  const { harvested } = concurrent
  assert.equal(harvested.board, state(0))
  boardTulipGain([a, b], concurrent.observations, [decorated.inventory, otherInitial.inventory], harvested.drops[0])
  assert.equal(harvested.drops.length, 1)
  assert.match(harvested.drops[0].id, /^minecraft:(orange|pink|red|white)_tulip$/)
  assert.equal(harvested.drops[0].count, 1)
  const repeatRead = async () => {
    const values = await Promise.all([a, b].map(bot => inspect(bot)))
    for (const value of values) assert.deepEqual(value.drops, harvested.drops)
    boardTulipGain([a, b], values, [decorated.inventory, otherInitial.inventory], harvested.drops[0])
    return values[1]
  }
  const repeated = await useAndObserve(b, () => use(b, harvested.boardHitbox), 'empty board stays empty',
    value => value.board === state(0), repeatRead, value => { assert.deepEqual(value.drops, harvested.drops) })
  assert.deepEqual(repeated.drops, harvested.drops)
  const cleanup = await collectBoardTulip(a, b, [decorated, otherInitial], harvested, spot)
  report.checks.tulipBoard = { consumed: 1, fullInventory: true, deniedInteraction: 1, deniedSpawn: 1,
    attemptedPlayers: concurrent.attemptedPlayers, acknowledged: concurrent.acknowledged,
    oldHitbox: concurrent.oldHitbox, newHitbox: concurrent.newHitbox, returned: harvested.drops[0], cleanup }
}
async function sofaStyle (a,b) {
  const style = (color, mode = 'single') => 'kaleidoscope_deco/kaleidoscope_bar/sofa/style/' + color + '/' + mode
  const initial = await inspect(a, 'sofa')
  await inspect(b, 'sofa')
  const spot = new Vec3(682.5, 200.45, 9.5)
  await Promise.all([a.lookAt(spot), b.lookAt(spot)])
  const use = state => a._client.write('use_entity', {
    target: state.sofaHitbox, mouse: 2, x: 0, y: .45, z: 0, hand: 0, sneaking: false
  })
  await selectHotbar(a, 0); await selectHotbar(b, 0)
  assert.equal(initial.sofa, style('none'))
  const canceled = await useAndObserve(a, () => use(initial), 'sofa trade cancellation recorded', value => value.canceledTrades === 1,
    inspect, value => { assert.equal(value.sofa, style('none')); assert.deepEqual(value.inventory, initial.inventory) })
  assert.equal(canceled.canceledTrades, 1); assert.equal(canceled.sofa, style('none'))
  assert.equal(count(canceled.inventory, 'minecraft:red_dye'), 3)
  const swapped = await useAndObserve(a, () => use(canceled), 'sofa hotbar switch rejection recorded',
    value => value.changedSlots === 1 && value.heldSlot === 1, inspect, value => {
      assert.equal(value.sofa, style('none')); assert.deepEqual(value.inventory, canceled.inventory)
    })
  assert.equal(swapped.changedSlots, 1); assert.equal(swapped.heldSlot, 1)
  assert.equal(swapped.sofa, style('none')); assert.deepEqual(swapped.inventory, canceled.inventory)
  await selectHotbar(a, 0)
  const red = await useAndObserve(a, () => use(swapped), 'red sofa and nested rejection committed',
    value => value.nestedSofaRejected && value.sofa === style('red') && count(value.inventory, 'minecraft:red_dye') === 2)
  assert.equal(red.nestedSofaRejected, true); assert.equal(red.sofa, style('red'))
  assert.equal(count(red.inventory, 'minecraft:red_dye'), 2)
  assert.equal(count((await inspect(b)).inventory, 'minecraft:blue_dye'), 6)
  const same = await useAndObserve(a, () => use(red), 'same-color sofa trade remains unchanged', value => value.sofa === style('red'),
    inspect, value => { assert.deepEqual(value.inventory, red.inventory) })
  assert.deepEqual(same.inventory, red.inventory)
  await selectHotbar(a, 2)
  const left = await useAndObserve(a, () => use(same), 'left sofa state committed', value => value.sofa === style('red', 'left'))
  assert.equal(left.sofa, style('red', 'left'))
  assert.deepEqual(left.inventory, red.inventory)
  const target = await until('sofa hitbox entity', () => a.entities[left.sofaHitbox])
  const replacementPosition = a.entity.position.clone()
  await parkFurniturePeer(b)
  await selectHotbar(a, 7)
  for (let i = 0; i < 3; ++i) { a.attack(target); await delay(250) }
  const removed = await observeSnapshot(a, 'styled sofa removal committed', value => value.sofa === undefined && count(value.drops, 'kaleidoscope_deco:kaleidoscope_bar/sofa') === 1)
  assert.equal(removed.sofa, undefined)
  assert.equal(count(removed.drops, 'kaleidoscope_deco:kaleidoscope_bar/sofa'), 1)
  const sofaItem = await pickUpFurniture(a, 'kaleidoscope_deco:kaleidoscope_bar/sofa', removed, spot, 'styled sofa pickup')
  assert.equal(sofaItem.owner, a.username)
  await walkTo(a, replacementPosition, 'return for styled sofa replacement')
  await selectHotbar(a, sofaItem.slot)
  await a.activateBlock(a.blockAt(new Vec3(682,199,9)), new Vec3(0,1,0), new Vec3(.5,1,.5))
  const restored = await observeSnapshot(a, 'styled sofa replacement committed',
    value => value.sofa === style('red', 'left') && count(value.inventory, 'kaleidoscope_deco:kaleidoscope_bar/sofa') === 0,
    bot => inspect(bot, 'inspectsofa'))
  assert.equal(restored.sofa, style('red', 'left'))
  assert.equal(count(restored.inventory, 'kaleidoscope_deco:kaleidoscope_bar/sofa'), 0)
  assert.equal(count(restored.inventory, 'minecraft:red_dye'), 2)
  report.checks.sofaStyle = { canceled: 1, switchedSlotRejected: 1, nestedPlayerRejected: true,
    dyeConsumed: 1, repeatedDyeConsumed: 0, droppedSofas: 1, restoredStyle: restored.sofa }
}
async function dollPose (a) {
  const id = 'kaleidoscope_doll:doll_0'
  const initial = await inspect(a, 'doll')
  assert.deepEqual(initial.dollPose.split(',').map(Number), [1,1,1,0,0,0,0,0,0])
  await a.lookAt(new Vec3(682.5, 200.45, 9.5))
  await selectHotbar(a, 0)
  a._client.write('use_entity', { target: initial.dollHitbox, mouse: 2, x: 0, y: .45, z: 0, hand: 0, sneaking: false })
  await until('nine axis pose menu', () => a.currentWindow?.inventoryStart === 36)
  await adjustDollAxes(a, value => value.dollPose)
  const changed = await inspect(a)
  assert.deepEqual(changed.dollPose.split(',').map(Number), [1.1,1.1,1.1,.05,.05,.05,15,15,15])
  a.closeWindow(a.currentWindow)
  await observeSnapshot(a, 'doll pose menu closed', value => value.view === 'CRAFTING')
  await selectHotbar(a, 7)
  const target = await until('doll hitbox entity', () => a.entities[changed.dollHitbox])
  const replacementPosition = a.entity.position.clone()
  for (let i = 0; i < 3; ++i) { a.attack(target); await delay(250) }
  const removed = await observeSnapshot(a, 'posed doll removal committed', value => value.dollPose === undefined && count(value.drops, id) === 1)
  assert.equal(removed.dollPose, undefined); assert.equal(count(removed.drops, id), 1)
  const recovered = await pickUpFurniture(a, id, removed, new Vec3(682.5, 200, 9.5), 'posed doll pickup')
  assert.equal(recovered.owner, a.username)
  await walkTo(a, replacementPosition, 'return for posed doll replacement')
  await selectHotbar(a, recovered.slot)
  await a.activateBlock(a.blockAt(new Vec3(682,199,9)), new Vec3(0,1,0), new Vec3(.5,1,.5))
  const restored = await observeSnapshot(a, 'posed doll replacement committed',
    value => value.dollPose === changed.dollPose && count(value.inventory, id) === 0, bot => inspect(bot, 'inspectdoll'))
  assert.equal(restored.dollPose, changed.dollPose); assert.equal(count(restored.inventory, id), 0)
  report.checks.dollPose = { axesAdjusted: 9, droppedDolls: 1, restoredPose: restored.dollPose }
}
async function main () {
  assert.ok(['single', 'seed', 'verify', 'empty'].includes(phase), 'Unknown scenario phase: ' + phase)
  assert.ok(['legacy', 'gameplay'].includes(scenario), 'Unknown scenario kind: ' + scenario)
  assert.ok(scenario !== 'gameplay' || phase !== 'single', 'Gameplay requires three explicit restart phases')
  const a = await connect('SenLuoProbeA'); const b = await connect('SenLuoProbeB')
  if (scenario === 'gameplay') {
    await require('./gameplay-persistence.cjs')({
      a, b, inspect, until, delay, click, count, Vec3, pickUpFurniture, observeSnapshot, useAndObserve,
      selectHotbar, walkTo, report, out, phase, stateFile: process.env.SENLUO_SCENARIO_STATE
    })
    report.passed = true
    return
  }
  const persistence = () => require('./persistence-players.cjs')({
    a, b, inspect, until, delay, click, count, Vec3, pickUpFurniture, observeSnapshot, useAndObserve, adjustDollAxes, report, out, phase,
    stateFile: process.env.SENLUO_SCENARIO_STATE
  })
  if (phase === 'verify' || phase === 'empty') {
    await persistence()
    report.passed = true
    return
  }
  if (phase === 'seed') await inspect(a, 'persistclear')
  const supplied = await inspect(a,'supply'); assert.deepEqual(supplied.placedSlots, []); await delay(200)
  await smith(a); await steamer(a); await scarecrow(a,b)
  try { await tulipBoard(a,b) } finally { await inspect(a, 'clearboard') }
  const peerPosition = b.entity.position.clone()
  try {
    try { await sofaStyle(a,b) } finally { await inspect(a, 'clearsofa') }
    try { await dollPose(a) } finally { await inspect(a, 'cleardoll') }
  } finally { await returnFurniturePeer(b, peerPosition) }
  if (phase === 'seed') await persistence()
  report.passed = true
}
main().catch(err => { report.error = err.stack; process.exitCode = 1 }).finally(async () => {
  shutdown.abort()
  for (const bot of sessions) bot.end()
  await Promise.allSettled([...pendingPacks])
  await Promise.allSettled([...pendingReaders])
  await delay(300)
  console.log(JSON.stringify(report,null,2)); fs.writeFileSync(path.join(out,'players.json'),JSON.stringify(report,null,2))
  process.exit(process.exitCode ?? 0)
})
