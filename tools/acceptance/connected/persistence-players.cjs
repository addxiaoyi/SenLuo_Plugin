'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const SOFA = 'kaleidoscope_deco:kaleidoscope_bar/sofa'
const DOLL = 'kaleidoscope_doll:doll_0'
const TUB = 'kaleidoscope_tavern:pressing_tub'
const BERRIES = 'minecraft:glow_berries'
const POSE = [1.1, 1.1, 1.1, .05, .05, .05, 15, 15, 15]
const STYLE = SOFA.replace(':', '/') + '/style/red/left'
const IDS = { sofa: SOFA, doll: DOLL, tub: TUB }

function canonical (value) {
  if (Array.isArray(value)) return value.map(canonical)
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]))
  return value
}
function holdings (items) {
  const totals = new Map()
  for (const item of items.filter(item => item.id)) {
    const { count, serialized, ...metadata } = item
    if (metadata.pdc && !Object.keys(metadata.pdc).length) delete metadata.pdc
    const signature = JSON.stringify(canonical(metadata))
    totals.set(signature, (totals.get(signature) || 0) + count)
  }
  return Object.fromEntries([...totals.entries()].sort(([a], [b]) => a.localeCompare(b)))
}
function fixture (snapshot, name) {
  const value = snapshot.persistence?.[name]
  assert.ok(value, 'Missing persistent fixture observation: ' + name)
  if (value.present) {
    assert.equal(value.id, IDS[name])
    assert.match(value.uuid, /^[0-9a-f-]{36}$/)
    assert.equal(value.world, 'acceptance-world')
    assert.equal(value.position.length, 3)
    assert.equal(value.blocked, false, name + ' must not be frozen by a rejected save')
    assert.ok(value.source && typeof value.source === 'object' && !Array.isArray(value.source))
    // CE's API-created default pose and empty containers may have no source item yet.
    if (Object.keys(value.source).length) {
      assert.equal(value.source.id, IDS[name])
      assert.equal(value.source.count, 1)
      assert.ok(value.source.serialized)
    }
  }
  return value
}
function statefulSource (value, name) {
  const description = 'Persisted ' + name + ' source item'
  assert.equal(value.source?.id, IDS[name], description + ' is missing')
  assert.equal(value.source.count, 1, description + ' count changed')
  assert.ok(value.source.serialized, description + ' lacks serialized components')
  const suffix = name === 'sofa' ? ':deco_state_v1' : ':pose_v1'
  const entries = Object.entries(value.source.pdc || {}).filter(([key]) => key.endsWith(suffix))
  assert.equal(entries.length, 1, description + ' lacks its state PDC')
  assert.ok(typeof entries[0][1].bytes === 'string' && entries[0][1].bytes.length > 0,
    description + ' has empty state PDC')
}
function assertReloaded (actual, expected, name) {
  assert.equal(actual.present, true, name + ' did not reload')
  for (const key of ['uuid', 'id', 'world', 'position', 'variant', 'source', 'saved', 'state', 'blocked', 'hasContents']) {
    assert.deepEqual(actual[key], expected[key], name + ' changed across the server restart: ' + key)
  }
}
function identities (snapshot) {
  return Object.fromEntries(Object.keys(IDS).map(name => {
    const { present, id, uuid, world, position } = fixture(snapshot, name)
    return [name, { present, id, uuid, world, position }]
  }))
}
function recordFixture (out, snapshot) {
  const file = path.join(out, 'fixture.json')
  const value = JSON.parse(fs.readFileSync(file, 'utf8'))
  value.persistence = identities(snapshot)
  fs.writeFileSync(file, JSON.stringify(value, null, 2))
}
function expectedHoldings (seed) {
  return holdings([
    ...Object.values(seed.players).flatMap(player => player.inventory),
    seed.fixtures.sofa.source, seed.fixtures.doll.source, seed.fixtures.tub.state.ingredients
  ])
}
function noLooseItems (snapshot) {
  assert.deepEqual(snapshot.persistence.looseItems, [], 'Uncollected or duplicated fixture items remain in the world')
}
async function selectEmptyHand (bot, snapshot, observeSnapshot) {
  const carrying = await snapshot(bot)
  const emptySlot = carrying.inventory.slice(0, 9).findIndex(item => !item.id)
  assert.ok(emptySlot >= 0, 'An empty hand is required to recover the tub ingredient')
  bot.setQuickBarSlot(emptySlot)
  const emptyHand = await observeSnapshot(bot, 'empty hand selection received', value => value.heldSlot === emptySlot, snapshot)
  assert.equal(emptyHand.heldSlot, emptySlot)
  assert.deepEqual(emptyHand.inventory[emptySlot], {})
  return emptyHand
}
function emptyFixtures (snapshot, seed) {
  assert.equal(fixture(snapshot, 'sofa').present, false, 'The recovered sofa reappeared')
  assert.equal(fixture(snapshot, 'doll').present, false, 'The recovered doll reappeared')
  const tub = fixture(snapshot, 'tub')
  assert.equal(tub.present, true)
  assert.equal(tub.uuid, seed.fixtures.tub.uuid, 'The empty tub must be the original furniture')
  assert.deepEqual(tub.position, seed.fixtures.tub.position)
  assert.deepEqual(tub.source, seed.fixtures.tub.source)
  assert.equal(tub.hasContents, false)
  assert.equal(tub.state.amount, 0)
  assert.deepEqual(tub.state.ingredients, {})
  noLooseItems(snapshot)
}

module.exports = async function persistenceScenario (h) {
  const { a, b, inspect, until, delay, count, Vec3, pickUpFurniture, observeSnapshot, useAndObserve, adjustDollAxes, report, out, phase, stateFile } = h
  assert.ok(stateFile && path.isAbsolute(stateFile), 'SENLUO_SCENARIO_STATE must be an absolute checkpoint path')
  assert.ok(['seed', 'verify', 'empty'].includes(phase))
  const players = [a, b]
  const snapshot = bot => inspect(bot, 'persistinspect')
  const observe = (bot, description, ready, invariant) => observeSnapshot(bot, description, ready, snapshot, invariant)
  const selectSlot = async (bot, slot) => {
    bot.setQuickBarSlot(slot)
    return observe(bot, 'persistent held hotbar slot ' + slot, value => value.heldSlot === slot)
  }
  const observeBoth = async () => ({ [a.username]: await snapshot(a), [b.username]: await snapshot(b) })
  const inventories = observations => Object.values(observations).flatMap(value => value.inventory)
  const use = async (bot, target, sneaking = false) => {
    const [x, y, z] = target.position
    await bot.lookAt(new Vec3(x, y + .45, z))
    await delay(200)
    bot._client.write('use_entity', { target: target.hitbox, mouse: 2, x: 0, y: .45, z: 0, hand: 0, sneaking })
  }
  const approach = async (bot, target, stoppingDistance = .85) => {
    const [x, , z] = target.position
    await bot.lookAt(new Vec3(x, bot.entity.position.y + 1.62, z))
    bot.setControlState('forward', true)
    try {
      await until('walk to the fixture position', () => Math.hypot(bot.entity.position.x - x, bot.entity.position.z - z) < stoppingDistance, 5000)
    } finally { bot.setControlState('forward', false) }
    await delay(350)
  }
  const recover = async (bot, name, expected) => {
    assert.equal(typeof pickUpFurniture, 'function', 'Furniture recovery requires the real network-drop pickup helper')
    const before = fixture(await snapshot(bot), name)
    assert.equal(before.uuid, expected.uuid)
    const [x, y, z] = before.position
    await bot.lookAt(new Vec3(x, y + .45, z))
    await selectSlot(bot, 7)
    const entity = await until(name + ' network hitbox', () => bot.entities[before.hitbox])
    for (let hit = 0; hit < 3; hit++) { bot.attack(entity); await delay(250) }
    const broken = await observe(bot, name + ' removal committed', value => !fixture(value, name).present && count(value.drops, IDS[name]) === 1,
      value => { assert.ok(count(value.drops, IDS[name]) <= 1, name + ' must produce exactly one drop') })
    assert.equal(fixture(broken, name).present, false)
    assert.equal(count(broken.drops, IDS[name]), 1, name + ' must produce exactly one drop')
    const picked = await pickUpFurniture(bot, IDS[name], broken, new Vec3(x, y, z), name + ' pickup', snapshot, players)
    assert.ok(picked?.snapshot && Number.isInteger(picked.slot) && picked.slot >= 0 && picked.slot < 9,
      name + ' pickup must return an observed hotbar item')
    assert.ok(players.some(player => player.username === picked.owner), name + ' pickup must identify its real participant')
    const pickedItem = picked.snapshot.inventory[picked.slot]
    assert.equal(pickedItem?.id, IDS[name])
    assert.equal(pickedItem.count, 1)
    assert.deepEqual(pickedItem.pdc, expected.source.pdc, name + ' recovery lost its source PDC')
    await until(name + ' pickup', async () => count(inventories(await observeBoth()), IDS[name]) === 1)
    const after = await observeBoth()
    assert.equal(count(inventories(after), IDS[name]), 1, name + ' must exist exactly once across both players')
    const reward = inventories(after).find(item => item.id === IDS[name])
    assert.deepEqual(reward.pdc, expected.source.pdc, name + ' recovery lost its source PDC')
    return { uuid: before.uuid, position: before.position, dropCount: 1, pickedUpBy: picked.owner, recovered: reward }
  }

  if (phase === 'seed') {
    const arranged = await inspect(a, 'persistseed')
    await inspect(b, 'persiststock')
    for (const name of Object.keys(IDS)) assert.equal(fixture(arranged, name).present, true)
    assert.equal(fixture(arranged, 'tub').hasContents, false)
    await selectSlot(a, 0)
    const red = await useAndObserve(a, () => use(a, fixture(arranged, 'sofa')), 'persistent red sofa committed',
      value => fixture(value, 'sofa').state.color === 'red' && count(value.inventory, 'minecraft:red_dye') === 2, snapshot)
    assert.equal(fixture(red, 'sofa').state.color, 'red')
    assert.equal(count(red.inventory, 'minecraft:red_dye'), 2)
    await selectSlot(a, 1)
    const styled = await useAndObserve(a, () => use(a, fixture(red, 'sofa')), 'persistent left sofa committed',
      value => fixture(value, 'sofa').variant === STYLE && fixture(value, 'sofa').state.mode === 'left', snapshot)
    assert.equal(fixture(styled, 'sofa').variant, STYLE)
    assert.equal(fixture(styled, 'sofa').state.mode, 'left')
    await selectSlot(a, 2)
    await use(a, fixture(styled, 'doll'))
    await until('persistent doll nine-axis menu', () => a.currentWindow?.inventoryStart === 36)
    await adjustDollAxes(a, value => fixture(value, 'doll').state.pose, snapshot)
    a.closeWindow(a.currentWindow)
    const posed = await observe(a, 'persistent doll pose menu closed', value => value.view === 'CRAFTING' &&
      JSON.stringify(fixture(value, 'doll').state.pose.split(',').map(Number)) === JSON.stringify(POSE))
    assert.deepEqual(fixture(posed, 'doll').state.pose.split(',').map(Number), POSE)
    statefulSource(fixture(posed, 'doll'), 'doll')
    await selectSlot(b, 0)
    const berry = (await snapshot(b)).inventory.find(item => item.id === BERRIES)
    assert.equal(berry.count, 1)
    assert.ok(berry.pdc['senluo_acceptance:restart_marker'])
    await useAndObserve(b, () => use(b, fixture(posed, 'tub')), 'marked tub ingredient deposited',
      value => fixture(value, 'tub').hasContents && fixture(value, 'tub').state.ingredients.id === BERRIES &&
        fixture(value, 'tub').state.ingredients.count === 1 && count(value.inventory, BERRIES) === 0, snapshot)
    const observations = await observeBoth()
    const final = observations[a.username]
    const tub = fixture(final, 'tub')
    assert.equal(tub.hasContents, true)
    assert.equal(tub.state.amount, 0)
    assert.equal(tub.state.ingredients.id, BERRIES)
    assert.equal(tub.state.ingredients.count, 1)
    assert.deepEqual(tub.state.ingredients.pdc, berry.pdc)
    assert.equal(count(inventories(observations), BERRIES), 0)
    noLooseItems(final)
    statefulSource(fixture(final, 'sofa'), 'sofa')
    statefulSource(fixture(final, 'doll'), 'doll')
    const seed = {
      schemaVersion: 1, phase: 'seed',
      fixtures: Object.fromEntries(Object.keys(IDS).map(name => [name, fixture(final, name)])),
      players: Object.fromEntries(players.map(bot => [bot.username, {
        uuid: observations[bot.username].playerUuid, inventory: observations[bot.username].inventory
      }]))
    }
    fs.writeFileSync(stateFile, JSON.stringify(seed, null, 2), { flag: 'wx' })
    recordFixture(out, final)
    report.checks.persistenceSeed = { fixtures: seed.fixtures, players: seed.players, sourceBerry: berry }
    return
  }

  const seed = JSON.parse(fs.readFileSync(stateFile, 'utf8'))
  assert.equal(seed.schemaVersion, 1)
  assert.equal(seed.phase, 'seed')
  statefulSource(seed.fixtures.sofa, 'sofa')
  statefulSource(seed.fixtures.doll, 'doll')
  const before = await observeBoth()
  for (const bot of players) assert.equal(before[bot.username].playerUuid, seed.players[bot.username].uuid)
  recordFixture(out, before[a.username])
  if (phase === 'empty') {
    emptyFixtures(before[a.username], seed)
    assert.deepEqual(holdings(inventories(before)), expectedHoldings(seed))
    await delay(2000)
    const later = await observeBoth()
    emptyFixtures(later[a.username], seed)
    assert.deepEqual(holdings(inventories(later)), expectedHoldings(seed))
    assert.deepEqual(later[a.username].drops, [])
    report.checks.persistenceEmpty = { fixtures: later[a.username].persistence, holdings: holdings(inventories(later)), elapsedObservationMs: 2000 }
    return
  }

  for (const name of Object.keys(IDS)) assertReloaded(fixture(before[a.username], name), seed.fixtures[name], name)
  for (const bot of players) assert.deepEqual(before[bot.username].inventory, seed.players[bot.username].inventory, 'Seed inventory changed while offline: ' + bot.username)
  noLooseItems(before[a.username])
  const sofa = await recover(a, 'sofa', seed.fixtures.sofa)
  const doll = await recover(b, 'doll', seed.fixtures.doll)
  // Walk around the crafting table at (681,200,7), then interact from the tub's unobstructed east side.
  await approach(b, { position: [683.25, 200, 9.5] }, .25)
  await approach(b, { position: [682.5, 200, 6.5] }, .25)
  await selectEmptyHand(b, snapshot, observeSnapshot)
  b.setControlState('sneak', true)
  await delay(200)
  try {
    const tub = fixture(await snapshot(b), 'tub')
    const taken = await useAndObserve(b, () => use(b, tub, true), 'marked tub ingredient returned',
      value => !fixture(value, 'tub').hasContents && count(value.inventory, BERRIES) === 1, snapshot)
    assert.equal(fixture(taken, 'tub').hasContents, false)
    assert.equal(fixture(taken, 'tub').state.amount, 0)
    assert.deepEqual(fixture(taken, 'tub').state.ingredients, {})
    assert.equal(count(taken.inventory, BERRIES), 1)
    assert.deepEqual(taken.inventory.find(item => item.id === BERRIES).pdc, seed.fixtures.tub.state.ingredients.pdc)
    // A repeated empty-hand request must not produce a second ingredient.
    const emptyHand = await selectEmptyHand(b, snapshot, observeSnapshot)
    await useAndObserve(b, () => use(b, fixture(emptyHand, 'tub'), true), 'repeated empty tub return received',
      value => !fixture(value, 'tub').hasContents && count(value.inventory, BERRIES) === 1, snapshot, value => {
        assert.equal(fixture(value, 'tub').hasContents, false)
        assert.deepEqual(value.inventory, taken.inventory)
      })
  } finally { b.setControlState('sneak', false) }
  const final = await observeBoth()
  emptyFixtures(final[a.username], seed)
  assert.deepEqual(holdings(inventories(final)), expectedHoldings(seed))
  assert.equal(count(final[a.username].drops, SOFA), 1)
  assert.equal(count(final[a.username].drops, DOLL), 1)
  assert.equal(count(final[a.username].drops, BERRIES), 0)
  report.checks.persistenceVerify = {
    reloaded: seed.fixtures, sofa, doll, returnedBerries: 1, repeatedReturn: 0,
    emptyFixtures: final[a.username].persistence, holdings: holdings(inventories(final))
  }
}
