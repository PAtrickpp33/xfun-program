// Storage for the web container, where this logic layer runs in a Web Worker.
//
// A worker cannot wait for the container, so `*Sync` storage calls had no way to
// return a value there. Instead the container hands the worker this app's stored
// entries once, before any app code runs ('storageSnapshot'). Reads are then
// answered from memory. Every write updates memory and is forwarded, in call
// order, to the container ('storageWrite'), which persists it with the same
// records as on every other path and reports back ('storageWriteResult').
//
// Failure is loud: if storage is unavailable at launch, or any write fails to
// persist, every later storage call fails too, so an app never trusts data it
// could not keep (e.g. a trade recovery journal).
import { isFunction } from '@dimina/common'

/** The container's (and Android's) per-app storage limit, in bytes. */
export const STORAGE_LIMIT_BYTES = 10 * 1024 * 1024

// Sizes follow the container's estimate: the stored record's length in UTF-16 bytes.
const VALUE_RECORD_LENGTH = '{"version":2,"kind":"value","dataType":"json","data":}'.length
const UNDEFINED_RECORD_LENGTH = '{"version":2,"kind":"value","dataType":"undefined"}'.length
/** wx failure payload ({ errMsg }), as fail callbacks and promise rejections carry it. */
const failPayload = errMsg => ({ errMsg })
const recordBytes = json => (json === null ? UNDEFINED_RECORD_LENGTH : VALUE_RECORD_LENGTH + json.length) * 2

export function storageError(name, reason) {
	const error = new Error(`${name}:fail ${reason}`)
	error.errMsg = error.message
	return error
}

/**
 * @param {(write: { seq: number, kind: 'set' | 'remove' | 'clear', key?: string, json?: string | null }) => void} persist
 *   forwards one write to the container; writes must arrive in the order given.
 */
export function createWebStore(persist) {
	let ready = false
	let failure = null
	let seq = 0
	let usedBytes = 0
	/** key -> JSON text of the value, or null for a stored `undefined`. */
	const entries = new Map()
	const waiting = new Map()

	function assertUsable(name) {
		if (failure !== null) throw storageError(name, failure)
	}

	function forward(write) {
		const id = ++seq
		persist({ seq: id, ...write })
		return id
	}

	function remember(key, json) {
		if (entries.has(key)) usedBytes -= recordBytes(entries.get(key))
		if (json === undefined) entries.delete(key)
		else {
			entries.set(key, json)
			usedBytes += recordBytes(json)
		}
	}

	return {
		/** True once the container has delivered this app's entries. */
		get ready() {
			return ready
		},

		init(snapshot) {
			if (ready) return // only the first snapshot is current; later writes live here
			ready = true
			if (!snapshot || snapshot.available !== true) {
				failure = `storage is unavailable${snapshot?.reason ? `: ${snapshot.reason}` : ''}`
				return
			}
			for (const entry of Array.isArray(snapshot.entries) ? snapshot.entries : []) {
				if (!Array.isArray(entry) || typeof entry[0] !== 'string') continue
				const [key, json] = entry
				if (json === null || typeof json === 'string') remember(key, json)
			}
		},

		settle(result) {
			if (!result || typeof result.seq !== 'number') return
			if (result.ok !== true && failure === null) {
				failure = `storage write failed: ${result.errMsg || 'unknown error'}`
				console.error(`[service] ${failure}`)
			}
			const pending = waiting.get(result.seq)
			if (!pending) return
			waiting.delete(result.seq)
			if (result.ok === true) pending.resolve()
			else pending.reject(result.errMsg || 'unknown error')
		},

		/** Resolves once the container has persisted write `id` (rejects with its error). */
		persisted(id) {
			return new Promise((resolve, reject) => waiting.set(id, { resolve, reject }))
		},

		get(name, key) {
			assertUsable(name)
			const id = String(key)
			if (!entries.has(id)) return { found: false }
			const json = entries.get(id)
			// Parse on every read: callers get their own copy, as from real storage.
			return { found: true, data: json === null ? undefined : JSON.parse(json) }
		},

		set(name, key, data) {
			assertUsable(name)
			const id = String(key)
			let json = null
			if (data !== undefined) {
				try {
					json = JSON.stringify(data)
				}
				catch (error) {
					throw storageError(name, `data cannot be stored: ${error?.message || error}`)
				}
				if (typeof json !== 'string') throw storageError(name, 'data cannot be stored')
			}
			const previous = entries.has(id) ? recordBytes(entries.get(id)) : 0
			if (usedBytes - previous + recordBytes(json) > STORAGE_LIMIT_BYTES) {
				throw storageError(name, 'exceed storage max size 10Mb')
			}
			remember(id, json)
			return forward({ kind: 'set', key: id, json })
		},

		remove(name, key) {
			assertUsable(name)
			const id = String(key)
			remember(id, undefined)
			return forward({ kind: 'remove', key: id })
		},

		clear(name) {
			assertUsable(name)
			entries.clear()
			usedBytes = 0
			return forward({ kind: 'clear' })
		},

		info(name) {
			assertUsable(name)
			return { keys: [...entries.keys()], currentSize: usedBytes, limitSize: STORAGE_LIMIT_BYTES }
		},
	}
}

/**
 * Runs one async storage call (setStorage, getStorage, ...) against the store,
 * with the usual success/fail/complete callbacks, or as a promise when none are
 * given. `run` does the work synchronously and returns the success payload and,
 * for writes, the write id whose persistence the call waits for.
 */
export function runAsyncStorageCall(store, name, opts, run) {
	const { success, fail, complete } = opts && typeof opts === 'object' ? opts : {}
	const outcome = new Promise((resolve, reject) => {
		let done
		try {
			done = run()
		}
		catch (error) {
			reject(failPayload(error?.errMsg || `${name}:fail ${error?.message || error}`))
			return
		}
		const finish = () => resolve({ ...done.result, errMsg: `${name}:ok` })
		if (done.write === undefined) finish()
		else store.persisted(done.write).then(finish, errMsg => reject(failPayload(`${name}:fail ${errMsg}`)))
	})
	if (![success, fail, complete].some(isFunction)) return outcome
	outcome.then(
		(result) => {
			if (isFunction(success)) success(result)
			if (isFunction(complete)) complete(result)
		},
		(error) => {
			if (isFunction(fail)) fail(error)
			if (isFunction(complete)) complete(error)
		},
	)
}
