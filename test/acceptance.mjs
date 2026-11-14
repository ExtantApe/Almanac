// Sundial on a throwaway Paper server, with a fake clock so months pass in seconds. Built in a temp folder on
// 127.0.0.1:25622 and deleted after. One schedule runs a plain marker command; the other runs a copy of a real monthly
// data pack, with a quiet area that a bot stands in.
//
//   AL_TEST_JARS   folder with paper-<version>.jar, multiverse-core-*.jar, and on 26.2+ ViaVersion-*.jar, ViaBackwards-*.jar
//   AL_TEST_EULA   an eula.txt already accepted. It's copied, never written.
//   AL_TEST_RUN    where the server is built. Must be under a temp folder.
//   AL_TEST_PACK   optional: a copy of a real monthly data pack with a rotate/to function taking {n:0-11}. Its
//                  namespace is read from the copy, so no server's names live in this repo. Without it, the made-up
//                  pack in test/fixture-pack stands in.
//   AL_TEST_PAPER  26.2 (default) or 26.3
//   AL_TEST_JAVA   a Java 25 to run Paper with
import { spawn } from 'node:child_process'
import { cpSync, copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import mineflayer from 'mineflayer'

const must = n => { const v = process.env[n]; if (!v) throw new Error('Set ' + n + ' (see the top of test/acceptance.mjs)'); return v }
const PAPER = process.env.AL_TEST_PAPER ?? '26.2', VIA = !PAPER.startsWith('26.1'), JAVA = process.env.AL_TEST_JAVA ?? 'java'
const JARS = must('AL_TEST_JARS'), EULA = must('AL_TEST_EULA'), RUN = path.resolve(must('AL_TEST_RUN'))
const PACK = process.env.AL_TEST_PACK ?? new URL('./fixture-pack', import.meta.url).pathname
if (![os.tmpdir(), '/tmp', '/private/tmp', '/var/folders'].map(p => path.resolve(p)).some(t => RUN.startsWith(t + path.sep))) throw new Error('AL_TEST_RUN must be under a temp folder')
if (!/^eula=true$/m.test(readFileSync(EULA, 'utf8'))) throw new Error('That eula.txt isn\'t accepted.')
const ROOT = new URL('../', import.meta.url).pathname
const JAR = readdirSync(path.join(ROOT, 'build/libs')).find(f => /^Sundial-[\d.]+\.jar$/.test(f))
if (!JAR) throw new Error('Run ./gradlew build first')
const NS = readdirSync(path.join(PACK, 'data')).find(d => existsSync(path.join(PACK, 'data', d, 'function/rotate')))
if (!NS) throw new Error('No data/<namespace>/function/rotate in ' + PACK)

const PORT = 25622, sleep = ms => new Promise(r => setTimeout(r, ms))
const jar = re => { const f = readdirSync(JARS).find(n => re.test(n)); if (!f) throw new Error('No jar ' + re); return path.join(JARS, f) }
const strip = l => l.replace(/^\[[\d:]+ \w+\]: /, '')
const results = []
const check = (name, ok, detail = '') => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`) }

const dir = path.join(RUN, 'sundial-' + PAPER)
rmSync(dir, { recursive: true, force: true })
mkdirSync(path.join(dir, 'plugins/Sundial'), { recursive: true })
copyFileSync(jar(new RegExp('^paper-' + PAPER.replace(/\./g, '\\.') + '\\.jar$')), path.join(dir, 'paper.jar'))
copyFileSync(EULA, path.join(dir, 'eula.txt'))
for (const re of [/^multiverse-core-.*\.jar$/, ...(VIA ? [/^ViaVersion-.*\.jar$/, /^ViaBackwards-.*\.jar$/] : [])]) { const j = jar(re); copyFileSync(j, path.join(dir, 'plugins', path.basename(j))) }
copyFileSync(path.join(ROOT, 'build/libs', JAR), path.join(dir, 'plugins/Sundial.jar'))
writeFileSync(path.join(dir, 'server.properties'), ['server-ip=127.0.0.1', `server-port=${PORT}`, 'online-mode=false', 'white-list=false', 'enforce-whitelist=false', 'level-type=minecraft\\:flat',
  'generate-structures=false', 'spawn-protection=0', 'difficulty=peaceful', 'spawn-monsters=false', 'view-distance=4', 'simulation-distance=4'].join('\n') + '\n')
const CLOCK = path.join(dir, 'test-clock.txt')
const setClock = iso => writeFileSync(CLOCK, new Date(iso).toISOString())
const CONFIG = (extra = '') => `timezone: "America/New_York"
schedules:
  marker:
    command: "say sundial-marker {month0} {month} {year}"
  course:
    command: "function ${NS}:rotate/to {n:{month0}}"
    quiet:
      world: lobby
      box: [3, -63, -45, 45, -26, -3]
      retry-minutes: 5
      give-up-after-hours: 6
${extra}`
writeFileSync(path.join(dir, 'plugins/Sundial/config.yml'), CONFIG('  broken:\n    command: "say {weekday}"\n'))

const log = []
let proc, exited
async function start() {
  const from = log.length
  proc = spawn(JAVA, ['-Xms1536M', '-Xmx1536M', '-Dterminal.jline=false', '-Dterminal.ansi=false', `-Dalmanac.test.clockFile=${CLOCK}`, '-Dalmanac.test.checkTicks=20',
    '-jar', 'paper.jar', 'nogui'], { cwd: dir, stdio: ['pipe', 'pipe', 'pipe'] })
  exited = new Promise(r => proc.on('exit', r))
  process.on('exit', () => { if (proc.exitCode === null) proc.kill('SIGKILL') })
  const add = d => { for (const l of d.toString().split('\n')) if (l.trim()) log.push(strip(l)) }
  proc.stdout.on('data', add); proc.stderr.on('data', add)
  await waitFor(/Done \(\d/, 240000, from)
  await sleep(2500)
}
async function waitFor(re, ms = 15000, from = 0) {
  const end = Date.now() + ms
  while (Date.now() < end) { const h = log.slice(from).find(l => re.test(l)); if (h) return h; if (proc.exitCode !== null) throw new Error('server exited'); await sleep(100) }
  throw new Error('Timed out waiting for ' + re)
}
async function ask(cmd, ms = 800) { const from = log.length; proc.stdin.write(cmd + '\n'); await sleep(ms); return log.slice(from) }
async function stop() { proc.stdin.write('stop\n'); if (!await Promise.race([exited.then(() => true), sleep(90000).then(() => false)])) { proc.kill('SIGKILL'); await exited } }
const ran = (re, from = 0) => log.slice(from).filter(l => re.test(l)).length
/** Moves the fake clock and waits for a few checks. */
async function moveTo(iso, ms = 3500) { const from = log.length; setClock(iso); await sleep(ms); return from }

console.log('== Paper ' + PAPER + (process.env.AL_TEST_PACK ? ', with a copy of a real pack' : ', with the made-up fixture pack'))
setClock('2026-11-15T12:00:00-05:00')
try {
  await start()
  await ask(`mv create lobby normal --world-type flat --no-structures`, 8000)
  cpSync(PACK, path.join(dir, 'world/datapacks', path.basename(PACK)), { recursive: true })
  await ask('reload confirm', 6000)
  await ask('sundial reload', 1500)
  check('a bad schedule is reported with its fix, and skipped', log.some(l => /Schedule broken uses \{weekday}.*\{month0}.*skipped; the others still run/.test(l)))
  check('a first install applies the current month', ran(/Schedule marker ran for 2026-11/) === 1, String(ran(/Schedule marker ran/)))

  let from = await moveTo('2026-12-01T03:30:00-05:00')
  check('nothing runs before 04:00 on the 1st', ran(/Schedule marker ran for 2026-12/, from) === 0)
  from = await moveTo('2026-12-01T04:01:00-05:00')
  check('after 04:00 on the 1st it runs once, with the right month', ran(/Schedule marker ran for 2026-12 \([^)]*\): say sundial-marker 11 12 2026/, from) === 1)
  check('the data pack schedule ran too, with nobody on the course', ran(/Schedule course ran for 2026-12/, from) === 1)
  const st = (await ask('sundial status', 1200)).join(' | ')
  check('status shows what was applied and what\'s next', /marker: applied 2026-12 \(dispatched/.test(st) && /next 2027-01-01 04:00/.test(st), st.slice(0, 220))
  const pv = (await ask('sundial preview course 3', 800)).join(' ')
  check('preview shows the command for a month without running it', new RegExp(`rotate/to \\{n:2\\} \\(not run\\)`).test(pv) && !log.some(l => /Schedule course ran for 2026-03/.test(l)), pv.slice(0, 160))

  // Restart mid-month: nothing runs. Then off over the 1st: it catches up once at the next start.
  await stop()
  setClock('2026-12-20T12:00:00-05:00')
  from = log.length
  await start()
  check('restarting mid-month runs nothing', ran(/Schedule marker ran/, from) === 0)
  await stop()
  setClock('2027-02-10T12:00:00-05:00')
  from = log.length
  await start(); await sleep(2000)
  check('off over the 1st: it catches up once at the next start, for the current month', ran(/Schedule marker ran for 2027-02 \([^)]*\): say sundial-marker 1 2 2027/, from) === 1 && ran(/Schedule marker ran/, from) === 1)

  // The quiet rule, with a bot on the course.
  const b = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: 'Runner', version: '26.1', auth: 'offline', viewDistance: 'tiny', hideErrors: true, physicsEnabled: false })
  if (VIA) { const w = b._client.write.bind(b._client); b._client.write = (n, d) => /^(position|position_look|look|flying)$/.test(n) ? undefined : w(n, d) }
  b.on('error', () => {})
  await new Promise((res, rej) => {
    b.once('spawn', res)
    b.once('kicked', r => rej(new Error('kicked: ' + (typeof r === 'string' ? r : JSON.stringify(r)))))
    b.once('end', r => rej(new Error('disconnected before spawning: ' + r)))
    setTimeout(() => rej(new Error('spawn timeout')), 60000)
  })
  await ask('mvtp Runner lobby', 2000); await ask('execute in minecraft:lobby run tp Runner 20 -60 -20', 1000)
  from = await moveTo('2027-03-01T04:01:00-05:00')
  check('someone on the course delays the course switch', ran(/Schedule course ran for 2027-03/, from) === 0 && ran(/Schedule course is due, but someone is in its quiet area/, from) === 1)
  check('a schedule without a quiet area isn\'t held up', ran(/Schedule marker ran for 2027-03/, from) === 1)
  await ask('execute in minecraft:lobby run tp Runner 100 -60 100', 1000)
  from = await moveTo('2027-03-01T04:03:00-05:00')
  check('not before the next retry', ran(/Schedule course ran for 2027-03/, from) === 0)
  from = await moveTo('2027-03-01T04:07:00-05:00')
  check('leaving lets it run within one retry', ran(/Schedule course ran for 2027-03 \(its quiet area is empty now\)/, from) === 1)
  await ask('execute in minecraft:lobby run tp Runner 20 -60 -20', 1000)
  from = await moveTo('2027-04-01T04:01:00-05:00')
  from = await moveTo('2027-04-01T10:02:00-05:00')
  check('staying past 6 hours: it runs anyway', ran(/Schedule course ran for 2027-04 \(someone was still in its quiet area after 6 hours\)/, from) === 1)
  await ask('mvtp Runner world', 1500)
  from = await moveTo('2027-05-01T04:01:00-05:00')
  check('someone in another world doesn\'t hold it up', ran(/Schedule course ran for 2027-05/, from) === 1)
  b.quit()

  // Running the same month again is harmless: the data pack makes repeats a no-op.
  from = log.length
  const r1 = (await ask('sundial run course', 2500)).join(' ')
  check('/sundial run applies the current month now, and running it again is harmless', /Ran course: function .*rotate\/to \{n:4\}/.test(r1) && !log.slice(from).some(l => /ERROR|Exception/.test(l)), r1.slice(0, 160))
  check('no Sundial errors in the log', !log.some(l => /Sundial/.test(l) && /(ERROR|Exception|SEVERE)/.test(l)))
  const rec = readFileSync(path.join(dir, 'plugins/Sundial/applied.yml'), 'utf8')
  check('applied.yml is plain YAML with each schedule\'s period, and no temp file is left', /course:\n  period: 2027-05/.test(rec) && !existsSync(path.join(dir, 'plugins/Sundial/applied.yml.tmp')), rec.slice(0, 160))
} catch (e) { check('the run finished', false, e.message); for (const l of log.filter(l => /error|missing|unbound|registry|failed to|> |runner|lost connection|kick|disconnect|via/i.test(l) && !/^\s+at /.test(l)).slice(0, 25)) console.log('  | ' + l) } finally { await stop(); rmSync(dir, { recursive: true, force: true }) }
const failed = results.filter(r => !r).length
console.log(`${results.length - failed}/${results.length} passed`)
if (failed) process.exitCode = 1
