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
const report = { passed: false, protocol: '1.21.11', packs: {}, checks: {} }
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
  const bot = mineflayer.createBot({ host: '127.0.0.1', port, version: '1.21.11', username, auth: 'offline', plugins: { resource_pack: false } })
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
  const before = await inspect(bot); assert.equal(before.top[3].id, ns+'netherite_kitchen_knife')
  await click(bot,3,1)
  const blocked = await inspect(bot)
  assert.equal(blocked.canceledSmith,1); assert.deepEqual(blocked.top,before.top); assert.deepEqual(blocked.cursor,{})
  assert.deepEqual(blocked.inventory,before.inventory)
  await click(bot,3)
  const single = await inspect(bot); assert.equal(single.cursor.id, ns+'netherite_kitchen_knife'); assert.equal(single.top[0].count,2); assert.equal(single.top[2].count,4); assert.deepEqual(single.top[1],{})
  await click(bot,hotbar(bot,0)); await move(bot,hotbar(bot,2),1); await click(bot,3,1)
  const shifted = await inspect(bot); assert.equal(count(shifted.inventory,ns+'netherite_kitchen_knife'),2)
  assert.equal(shifted.top[0].count,1); assert.equal(shifted.top[2].count,3); assert.deepEqual(shifted.top[1],{}); assert.deepEqual(shifted.cursor,{})
  bot.closeWindow(bot.currentWindow); await delay(200)
  const closed = await inspect(bot)
  assert.equal(count(closed.inventory,'minecraft:netherite_ingot'),3); assert.equal(count(closed.inventory,'minecraft:netherite_upgrade_smithing_template'),1)
  report.checks.smithing = { canceledShift: 1, productsWithPreservedComponents: 2, remainingTemplates: 1, remainingIngots: 3 }
}
async function steamer (bot) {
  await open(bot,681,'crafting')
  await move(bot,hotbar(bot,4),1); await click(bot,hotbar(bot,5))
  for (const slot of [2,3,4,5]) await click(bot,slot,0,1)
  const before = await inspect(bot); const slots = Array(4).fill(ns+'raw_dough*1')
  assert.deepEqual(before.top[0].slots,slots)
  await click(bot,0); await click(bot,hotbar(bot,4)); bot.closeWindow(bot.currentWindow); await delay(200)
  const crafted = await inspect(bot); const basket = crafted.inventory.findIndex(item => item.id === ns+'steamer' && item.slots.join() === slots.join()); assert.ok(basket >= 0 && basket < 9)
  assert.equal(count(crafted.inventory,ns+'raw_dough'),0); bot.setQuickBarSlot(basket); await delay(150)
  await bot.activateBlock(bot.blockAt(new Vec3(680,199,8)),new Vec3(0,1,0),new Vec3(.5,1,.5)); await delay(250)
  const placed = await inspect(bot); assert.deepEqual(placed.placedSlots.slice(0,4),slots); assert.equal(count(placed.inventory,ns+'steamer'),0)
  const pos = new Vec3(680,200,8)
  const take = async () => { await bot.activateBlock(bot.blockAt(pos)); await delay(220); return inspect(bot) }
  bot.setQuickBarSlot(6); await delay(120)
  const emptied = await take(); assert.ok(emptied.placedSlots.length && emptied.placedSlots.every(slot => slot === 'empty'))
  assert.equal(count(emptied.inventory,ns+'raw_dough'),4)
  bot.setQuickBarSlot(7); await delay(120)
  const recovered = await take(); assert.deepEqual(recovered.placedSlots,[])
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
  b.attack(targetB); await delay(350)
  const protectedHit = await inspect(b); assert.equal(protectedHit.scarecrow,true); assert.deepEqual(protectedHit.hits.map(h=>h.canceled),[true]); assert.deepEqual(protectedHit.drops,[])
  a.attack(targetA); await delay(85); b.attack(targetB); await delay(350)
  const final = await inspect(a); assert.equal(final.scarecrow,false); assert.deepEqual(final.hits.map(h=>h.canceled),[true,true,false]); assert.deepEqual(final.hits.map(h=>h.player),[b.username,a.username,b.username]); assert.ok(final.hits.every(h=>h.regionOwned)); assert.ok(final.hits[2].tick-final.hits[1].tick<=5)
  assert.equal(count(final.drops,ns+'scarecrow'),1)
  report.checks.scarecrow = { hits: final.hits, drops: final.drops }
}
async function tulipBoard (a,b) {
  const state = n => 'kaleidoscope_deco/kaleidoscope_bar/blackboard/' + n
  const initial = await inspect(a, 'tulip')
  await inspect(b, 'tulip')
  assert.equal(initial.board, state(0))
  const spot = new Vec3(682.5, 200.45, 9.5)
  await Promise.all([a.lookAt(spot), b.lookAt(spot)])
  const use = (bot, id) => bot._client.write('use_entity', {
    target: id, mouse: 2, x: 0, y: .45, z: 0, hand: 0, sneaking: false
  })
  a.setQuickBarSlot(0); b.setQuickBarSlot(1); await delay(200)
  use(a, initial.boardHitbox); await delay(200)
  const decorated = await inspect(a)
  assert.equal(decorated.board, state(9))
  assert.equal(decorated.inventory[0].count, 1)
  assert.ok(decorated.inventory.every(item => item.count > 0))
  assert.deepEqual(decorated.drops, [])
  a.setQuickBarSlot(1); await delay(200)
  use(b, decorated.boardHitbox); await delay(200)
  const protectedBoard = await inspect(b)
  assert.equal(protectedBoard.boardDenied, 1); assert.equal(protectedBoard.flowerDenied, 0)
  assert.equal(protectedBoard.board, state(9)); assert.deepEqual(protectedBoard.drops, [])
  use(a, protectedBoard.boardHitbox); await delay(200)
  const blockedDrop = await inspect(a)
  assert.equal(blockedDrop.flowerDenied, 1); assert.equal(blockedDrop.board, state(9))
  assert.deepEqual(blockedDrop.inventory, decorated.inventory); assert.deepEqual(blockedDrop.drops, [])
  use(a, blockedDrop.boardHitbox); use(b, blockedDrop.boardHitbox); await delay(200)
  const harvested = await inspect(a)
  assert.equal(harvested.board, state(0)); assert.deepEqual(harvested.inventory, decorated.inventory)
  assert.equal(harvested.drops.length, 1)
  assert.match(harvested.drops[0].id, /^minecraft:(orange|pink|red|white)_tulip$/)
  assert.equal(harvested.drops[0].count, 1)
  use(b, harvested.boardHitbox); await delay(200)
  assert.deepEqual((await inspect(b)).drops, harvested.drops)
  report.checks.tulipBoard = { consumed: 1, fullInventory: true, deniedInteraction: 1, deniedSpawn: 1, returned: harvested.drops[0] }
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
  a.setQuickBarSlot(0); b.setQuickBarSlot(0); await delay(200)
  assert.equal(initial.sofa, style('none'))
  use(initial); await delay(200)
  const canceled = await inspect(a)
  assert.equal(canceled.canceledTrades, 1); assert.equal(canceled.sofa, style('none'))
  assert.equal(count(canceled.inventory, 'minecraft:red_dye'), 3)
  use(canceled); await delay(200)
  const swapped = await inspect(a)
  assert.equal(swapped.changedSlots, 1); assert.equal(swapped.heldSlot, 1)
  assert.equal(swapped.sofa, style('none')); assert.deepEqual(swapped.inventory, canceled.inventory)
  a.setQuickBarSlot(0); await delay(200)
  use(swapped); await delay(200)
  const red = await inspect(a)
  assert.equal(red.nestedSofaRejected, true); assert.equal(red.sofa, style('red'))
  assert.equal(count(red.inventory, 'minecraft:red_dye'), 2)
  assert.equal(count((await inspect(b)).inventory, 'minecraft:blue_dye'), 6)
  use(red); await delay(200)
  const same = await inspect(a)
  assert.deepEqual(same.inventory, red.inventory)
  a.setQuickBarSlot(2); await delay(200)
  use(same); await delay(200)
  const left = await inspect(a)
  assert.equal(left.sofa, style('red', 'left'))
  assert.deepEqual(left.inventory, red.inventory)
  const target = await until('sofa hitbox entity', () => a.entities[left.sofaHitbox])
  b.setControlState('back', true); await delay(400); b.setControlState('back', false)
  a.setQuickBarSlot(7); await delay(200)
  for (let i = 0; i < 3; ++i) { a.attack(target); await delay(250) }
  const removed = await inspect(a)
  assert.equal(removed.sofa, undefined)
  assert.equal(count(removed.drops, 'kaleidoscope_deco:kaleidoscope_bar/sofa'), 1)
  a.setControlState('forward', true); await delay(300); a.setControlState('forward', false)
  const sofaItem = await until('styled sofa pickup', async () => {
    const snapshot = await inspect(a)
    const slot = snapshot.inventory.findIndex(item => item.id === 'kaleidoscope_deco:kaleidoscope_bar/sofa')
    return slot < 0 ? false : { slot }
  })
  assert.ok(sofaItem.slot < 9, 'Expected the recovered sofa in an empty hotbar slot')
  a.setControlState('back', true); await delay(300); a.setControlState('back', false)
  a.setQuickBarSlot(sofaItem.slot); await delay(200)
  await a.activateBlock(a.blockAt(new Vec3(682,199,9)), new Vec3(0,1,0), new Vec3(.5,1,.5))
  await delay(300)
  const restored = await inspect(a, 'inspectsofa')
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
  a.setQuickBarSlot(0); await delay(200)
  a._client.write('use_entity', { target: initial.dollHitbox, mouse: 2, x: 0, y: .45, z: 0, hand: 0, sneaking: false })
  await until('nine axis pose menu', () => a.currentWindow?.inventoryStart === 36)
  for (let axis = 0; axis < 9; axis++) {
    await click(a, 18 + axis)
    await until('refreshed pose menu', () => a.currentWindow?.inventoryStart === 36)
  }
  const changed = await inspect(a)
  assert.deepEqual(changed.dollPose.split(',').map(Number), [1.1,1.1,1.1,.05,.05,.05,15,15,15])
  a.closeWindow(a.currentWindow); await delay(200)
  a.setQuickBarSlot(7); await delay(200)
  const target = await until('doll hitbox entity', () => a.entities[changed.dollHitbox])
  for (let i = 0; i < 3; ++i) { a.attack(target); await delay(250) }
  const removed = await inspect(a)
  assert.equal(removed.dollPose, undefined); assert.equal(count(removed.drops, id), 1)
  a.setControlState('forward', true); await delay(300); a.setControlState('forward', false)
  const recovered = await until('posed doll pickup', async () => {
    const snapshot = await inspect(a)
    const slot = snapshot.inventory.findIndex(item => item.id === id)
    return slot < 0 ? false : { slot }
  })
  assert.ok(recovered.slot < 9)
  a.setControlState('back', true); await delay(300); a.setControlState('back', false)
  a.setQuickBarSlot(recovered.slot); await delay(200)
  await a.activateBlock(a.blockAt(new Vec3(682,199,9)), new Vec3(0,1,0), new Vec3(.5,1,.5))
  await delay(300)
  const restored = await inspect(a, 'inspectdoll')
  assert.equal(restored.dollPose, changed.dollPose); assert.equal(count(restored.inventory, id), 0)
  report.checks.dollPose = { axesAdjusted: 9, droppedDolls: 1, restoredPose: restored.dollPose }
}
async function main () {
  const a = await connect('SenLuoProbeA'); const b = await connect('SenLuoProbeB')
  const supplied = await inspect(a,'supply'); assert.deepEqual(supplied.placedSlots, []); await delay(200)
  await smith(a); await steamer(a); await scarecrow(a,b)
  try { await tulipBoard(a,b) } finally { await inspect(a, 'clearboard') }
  try { await sofaStyle(a,b) } finally { await inspect(a, 'clearsofa') }
  try { await dollPose(a) } finally { await inspect(a, 'cleardoll') }
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
