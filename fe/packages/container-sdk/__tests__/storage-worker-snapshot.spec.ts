import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createContainer } from '../src/index.js'
import { FakeWorker, resetFakeWorker } from './fixtures/fake-worker.js'
import { installFetchMock } from './fixtures/mock-fetch.js'

// The logic layer runs in a Web Worker, so wx.*StorageSync is answered there
// from a snapshot this container sends before any app code runs; the worker
// forwards each write back here to be persisted (see service storage/web-store.js).

const record = (data: unknown) => JSON.stringify({ version: 2, kind: 'value', dataType: 'json', data })
const v2Key = (appId: string, key: string) => `__dimina_storage_v2_data__${appId.length}:${appId}:${key}`

async function openApp(appId: string, options: Record<string, unknown> = {}) {
	const mount = document.createElement('div')
	document.body.appendChild(mount)
	const container = createContainer({ mount, ...options })
	const index = FakeWorker.instances.length
	const miniApp = await container.openApp({ appId, path: 'pages/index/index' })
	await vi.waitFor(() => expect(FakeWorker.instances.length).toBeGreaterThan(index))
	return { miniApp, worker: FakeWorker.instances[index] }
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type WorkerMessage = { type?: string, body?: any }
const messagesOf = (worker: FakeWorker, type: string): WorkerMessage[] =>
	worker.postMessage.mock.calls.map(([message]: [WorkerMessage]) => message).filter((message: WorkerMessage) => message?.type === type)

function workerWrites(worker: FakeWorker, body: Record<string, unknown>) {
	worker.onmessage?.({ data: { method: 'invoke', type: 'storageWrite', body } } as MessageEvent)
	return messagesOf(worker, 'storageWriteResult').at(-1)?.body
}

describe('storage for the Web Worker logic layer', () => {
	beforeEach(() => {
		installFetchMock()
		resetFakeWorker()
		localStorage.clear()
	})

	it('sends the app its own entries before any resource loads', async () => {
		localStorage.setItem(v2Key('demo', 'favorites'), record(['BTC']))
		localStorage.setItem(v2Key('demo', 'gone'), JSON.stringify({ version: 2, kind: 'deleted' }))
		localStorage.setItem(v2Key('demo', 'nothing'), JSON.stringify({ version: 2, kind: 'value', dataType: 'undefined' }))
		localStorage.setItem('demo_token', JSON.stringify('legacy-token'))
		localStorage.setItem('demo_a_b', JSON.stringify('ambiguous legacy key'))
		localStorage.setItem(v2Key('other', 'secret'), record('not yours'))

		const { worker } = await openApp('demo')
		const calls: Array<string | undefined> = worker.postMessage.mock.calls.map(([message]: [WorkerMessage]) => message?.type)
		expect(calls[0]).toBe('storageSnapshot')
		expect(calls.indexOf('storageSnapshot')).toBeLessThan(calls.indexOf('loadResource'))

		const [snapshot] = messagesOf(worker, 'storageSnapshot')
		expect(snapshot.body.available).toBe(true)
		expect(Object.fromEntries(snapshot.body.entries)).toEqual({ favorites: '["BTC"]', nothing: null, token: '"legacy-token"' })
	}, 10000)

	it('persists worker writes in the same records as the async APIs and reports each one', async () => {
		const { miniApp, worker } = await openApp('demo')

		expect(workerWrites(worker, { seq: 1, kind: 'set', key: 'journal', json: '[{"id":"r1"}]' })).toEqual({ seq: 1, ok: true })
		expect(localStorage.getItem(v2Key('demo', 'journal'))).toBe(record([{ id: 'r1' }]))

		miniApp.invokeApi('getStorage', { key: 'journal', success: 'get-ok', fail: 'get-fail' })
		const reply = await vi.waitFor(() => {
			const found = messagesOf(worker, 'triggerCallback').find(message => message.body?.id === 'get-ok')
			if (!found) throw new Error('no getStorage reply yet')
			return found
		})
		expect(reply.body.args.data).toEqual([{ id: 'r1' }])

		expect(workerWrites(worker, { seq: 2, kind: 'set', key: 'empty', json: null })).toEqual({ seq: 2, ok: true })
		expect(localStorage.getItem(v2Key('demo', 'empty'))).toBe(JSON.stringify({ version: 2, kind: 'value', dataType: 'undefined' }))

		expect(workerWrites(worker, { seq: 3, kind: 'remove', key: 'journal' })).toEqual({ seq: 3, ok: true })
		expect(localStorage.getItem(v2Key('demo', 'journal'))).toBe(JSON.stringify({ version: 2, kind: 'deleted' }))

		localStorage.setItem(v2Key('other', 'keep'), record(1))
		expect(workerWrites(worker, { seq: 4, kind: 'clear' })).toEqual({ seq: 4, ok: true })
		expect(localStorage.getItem(v2Key('demo', 'empty'))).toBeNull()
		expect(localStorage.getItem(v2Key('other', 'keep'))).toBe(record(1))
	}, 10000)

	it('reports unusable storage instead of pretending to keep data', async () => {
		vi.spyOn(console, 'error').mockImplementation(() => {})
		const { worker } = await openApp('demo', { storageSync: false })
		const [snapshot] = messagesOf(worker, 'storageSnapshot')
		expect(snapshot.body).toMatchObject({ available: false, entries: [] })
		expect(snapshot.body.reason).toContain('storageSync is disabled')

		expect(workerWrites(worker, { seq: 1, kind: 'set', key: 'journal', json: '[]' })).toMatchObject({ seq: 1, ok: false })
	}, 10000)

	it('reports a write the browser refuses, and malformed writes', async () => {
		vi.spyOn(console, 'error').mockImplementation(() => {})
		const failing = {
			getItem: (key: string) => localStorage.getItem(key),
			setItem: (key: string, value: string) => {
				if (key.endsWith(':journal')) throw new Error('QuotaExceededError')
				localStorage.setItem(key, value)
			},
			removeItem: (key: string) => localStorage.removeItem(key),
			key: (index: number) => localStorage.key(index),
			get length() {
				return localStorage.length
			},
		}
		const { worker } = await openApp('demo', { storageSync: failing })
		expect(messagesOf(worker, 'storageSnapshot')[0].body.available).toBe(true)

		expect(workerWrites(worker, { seq: 7, kind: 'set', key: 'journal', json: '[]' })).toEqual({ seq: 7, ok: false, errMsg: 'QuotaExceededError' })
		expect(workerWrites(worker, { seq: 8, kind: 'set', key: 7, json: '[]' })).toEqual({ seq: 8, ok: false, errMsg: 'invalid storage write' })
		expect(workerWrites(worker, { seq: 9, kind: 'drop' })).toEqual({ seq: 9, ok: false, errMsg: 'invalid storage write' })
	}, 10000)
})
