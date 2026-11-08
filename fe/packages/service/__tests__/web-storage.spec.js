import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Web container: the logic layer runs in a Web Worker, so wx.*StorageSync is
// served from the snapshot the container sends before app code runs.

let posted

async function loadStorage({ webWorker = true } = {}) {
	vi.resetModules()
	vi.doMock('@dimina/common', async importOriginal => ({ ...await importOriginal(), isWebWorker: webWorker }))
	posted = []
	globalThis.postMessage = vi.fn(message => posted.push(message))
	globalThis.DiminaServiceBridge = { onMessage: null, invoke: vi.fn(() => 'native-result'), publish: vi.fn() }
	const storage = await import('../src/api/core/storage/index.js')
	const { default: message } = await import('../src/core/message.js')
	const deliver = (type, body) => message.handleMsg({ type, body })
	return { storage, deliver }
}

const writes = () => posted.filter(m => m.type === 'storageWrite').map(m => m.body)
const settle = (deliver, ok = true, errMsg) => {
	for (const write of writes()) deliver('storageWriteResult', ok ? { seq: write.seq, ok: true } : { seq: write.seq, ok: false, errMsg })
}

describe('wx storage in the web worker', () => {
	beforeEach(() => vi.spyOn(console, 'log').mockImplementation(() => {}))
	afterEach(() => {
		vi.doUnmock('@dimina/common')
		vi.restoreAllMocks()
		delete globalThis.DiminaServiceBridge
	})

	it('reads the snapshot synchronously and returns fresh copies', async () => {
		const { storage, deliver } = await loadStorage()
		deliver('storageSnapshot', { available: true, entries: [['journal', '[{"id":1}]'], ['empty', null]] })

		const first = storage.getStorageSync('journal')
		expect(first).toEqual([{ id: 1 }])
		first.push({ id: 2 })
		expect(storage.getStorageSync('journal')).toEqual([{ id: 1 }])
		expect(storage.getStorageSync('empty')).toBeUndefined()
		expect(storage.getStorageSync('missing')).toBe('')
		expect(globalThis.DiminaServiceBridge.invoke).not.toHaveBeenCalled()
	})

	it('applies writes at once and forwards them to the container in call order', async () => {
		const { storage, deliver } = await loadStorage()
		deliver('storageSnapshot', { available: true, entries: [['old', '1']] })

		storage.setStorageSync('favorites', ['BTC', 'ETH'])
		expect(storage.getStorageSync('favorites')).toEqual(['BTC', 'ETH'])
		storage.removeStorageSync('old')
		expect(storage.getStorageSync('old')).toBe('')
		storage.setStorageSync('nothing', undefined)
		storage.clearStorageSync()
		expect(storage.getStorageInfoSync().keys).toEqual([])

		expect(writes()).toEqual([
			{ seq: 1, kind: 'set', key: 'favorites', json: '["BTC","ETH"]' },
			{ seq: 2, kind: 'remove', key: 'old' },
			{ seq: 3, kind: 'set', key: 'nothing', json: null },
			{ seq: 4, kind: 'clear' },
		])
		expect(posted.every(m => m.method === 'invoke')).toBe(true)
	})

	it('reports keys and sizes like the container', async () => {
		const { storage, deliver } = await loadStorage()
		deliver('storageSnapshot', { available: true, entries: [] })
		storage.setStorageSync('a', 'x')
		const record = JSON.stringify({ version: 2, kind: 'value', dataType: 'json', data: 'x' })
		expect(storage.getStorageInfoSync()).toEqual({ keys: ['a'], currentSize: record.length * 2, limitSize: 10 * 1024 * 1024 })
	})

	it('fails every call when the container cannot store data', async () => {
		const { storage, deliver } = await loadStorage()
		deliver('storageSnapshot', { available: false, reason: 'SecurityError', entries: [] })

		expect(() => storage.setStorageSync('journal', [])).toThrow('setStorageSync:fail storage is unavailable: SecurityError')
		expect(() => storage.getStorageSync('journal')).toThrow('getStorageSync:fail')
		try {
			storage.setStorageSync('journal', [])
		}
		catch (error) {
			expect(error.errMsg).toBe('setStorageSync:fail storage is unavailable: SecurityError')
		}
		expect(writes()).toEqual([])
	})

	it('stops trusting storage after a write fails to persist', async () => {
		const { storage, deliver } = await loadStorage()
		deliver('storageSnapshot', { available: true, entries: [] })
		vi.spyOn(console, 'error').mockImplementation(() => {})

		storage.setStorageSync('journal', [{ id: 1 }])
		settle(deliver, false, 'QuotaExceededError')
		// Results are business messages; let the background scheduler deliver them.
		await vi.waitFor(() => expect(() => storage.setStorageSync('journal', [])).toThrow('storage write failed: QuotaExceededError'))
	})

	it('rejects values that cannot be stored, and the 10 MB limit, before writing', async () => {
		const { storage, deliver } = await loadStorage()
		deliver('storageSnapshot', { available: true, entries: [] })
		const circular = {}
		circular.self = circular

		expect(() => storage.setStorageSync('bad', circular)).toThrow('setStorageSync:fail data cannot be stored')
		expect(() => storage.setStorageSync('fn', () => {})).toThrow('setStorageSync:fail data cannot be stored')
		expect(() => storage.setStorageSync('big', 'x'.repeat(5.3 * 1024 * 1024))).toThrow('exceed storage max size 10Mb')
		expect(storage.getStorageSync('big')).toBe('')
		expect(writes()).toEqual([])
	})

	it('answers async calls once the write is persisted, with callbacks or a promise', async () => {
		const { storage, deliver } = await loadStorage()
		deliver('storageSnapshot', { available: true, entries: [] })

		const success = vi.fn()
		const complete = vi.fn()
		storage.setStorage({ key: 'k', data: 1, success, complete })
		expect(storage.getStorageSync('k')).toBe(1)
		await Promise.resolve()
		expect(success).not.toHaveBeenCalled() // not persisted yet
		settle(deliver)
		await vi.waitFor(() => expect(success).toHaveBeenCalledWith({ errMsg: 'setStorage:ok' }))
		expect(complete).toHaveBeenCalledTimes(1)

		await expect(storage.getStorage({ key: 'k' })).resolves.toEqual({ data: 1, errMsg: 'getStorage:ok' })
		await expect(storage.getStorage({ key: 'none' })).rejects.toEqual({ errMsg: 'getStorage:fail data not found' })
		await expect(storage.getStorageInfo()).resolves.toMatchObject({ keys: ['k'], errMsg: 'getStorageInfo:ok' })

		const removed = storage.removeStorage({ key: 'k' })
		expect(storage.getStorageSync('k')).toBe('')
		posted.length = 0
		storage.clearStorage({ fail: vi.fn() })
		const [removeWrite] = writes()
		expect(removeWrite).toEqual({ seq: 3, kind: 'clear' })
		deliver('storageWriteResult', { seq: 2, ok: false, errMsg: 'disk full' })
		await expect(removed).rejects.toEqual({ errMsg: 'removeStorage:fail disk full' })
	})

	it('keeps the first snapshot: later state lives in the worker', async () => {
		const { storage, deliver } = await loadStorage()
		deliver('storageSnapshot', { available: true, entries: [['a', '1']] })
		storage.setStorageSync('a', 2)
		deliver('storageSnapshot', { available: true, entries: [['a', '1']] })
		expect(storage.getStorageSync('a')).toBe(2)
	})

	it('keeps the native bridge outside a web worker', async () => {
		const { storage } = await loadStorage({ webWorker: false })
		expect(storage.getStorageSync('k')).toBe('native-result')
		expect(globalThis.DiminaServiceBridge.invoke).toHaveBeenCalledWith(expect.objectContaining({ body: expect.objectContaining({ name: 'getStorageSync', params: 'k' }) }))
		storage.setStorageSync('k', 1)
		expect(globalThis.DiminaServiceBridge.invoke).toHaveBeenLastCalledWith(expect.objectContaining({ body: expect.objectContaining({ name: 'setStorageSync', params: ['k', 1] }) }))
	})
})
