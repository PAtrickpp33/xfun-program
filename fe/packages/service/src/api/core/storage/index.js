import { isWebWorker } from '@dimina/common'
import { invokeAPI } from '@/api/common'
import message from '@/core/message'
import { createWebStore, runAsyncStorageCall, storageError } from './web-store'

// Only wx storage APIs may be exported from this file: every export of
// core/**/index.js becomes a public wx API.

// Web container (Web Worker): storage is answered from a snapshot the container
// sends before any app code runs; native hosts keep their synchronous bridge.
const webStore = createWebStore(write => message.invoke({ type: 'storageWrite', body: write }))
if (isWebWorker) {
	message.on('storageSnapshot', snapshot => webStore.init(snapshot))
	message.on('storageWriteResult', result => webStore.settle(result))
}
const useWebStore = () => isWebWorker && webStore.ready

/**
 * https://developers.weixin.qq.com/miniprogram/dev/api/storage/wx.setStorageSync.html
 */
export function setStorageSync(...opts) {
	if (useWebStore()) {
		webStore.set('setStorageSync', opts[0], opts[1])
		return
	}
	return invokeAPI('setStorageSync', opts)
}

/**
 * 从本地缓存中同步获取指定 key 的内容。
 * https://developers.weixin.qq.com/miniprogram/dev/api/storage/wx.getStorageSync.html
 */
export function getStorageSync(opts) {
	if (useWebStore()) {
		const value = webStore.get('getStorageSync', opts)
		// A missing key reads as an empty string, as on Android and in WeChat.
		return value.found ? value.data : ''
	}
	return invokeAPI('getStorageSync', opts)
}

/**
 *
 * https://developers.weixin.qq.com/miniprogram/dev/api/storage/wx.removeStorageSync.html
 */
export function removeStorageSync(opts) {
	if (useWebStore()) {
		webStore.remove('removeStorageSync', opts)
		return
	}
	return invokeAPI('removeStorageSync', opts)
}

/**
 * https://developers.weixin.qq.com/miniprogram/dev/api/storage/wx.clearStorageSync.html
 */
export function clearStorageSync() {
	if (useWebStore()) {
		webStore.clear('clearStorageSync')
		return
	}
	return invokeAPI('clearStorageSync')
}

/**
 * https://developers.weixin.qq.com/miniprogram/dev/api/storage/wx.setStorage.html
 */
export function setStorage(opts) {
	if (useWebStore()) {
		return runAsyncStorageCall(webStore, 'setStorage', opts, () => ({ write: webStore.set('setStorage', opts?.key, opts?.data) }))
	}
	return invokeAPI('setStorage', opts)
}

/**
 * https://developers.weixin.qq.com/miniprogram/dev/api/storage/wx.getStorage.html
 */
export function getStorage(opts) {
	if (useWebStore()) {
		return runAsyncStorageCall(webStore, 'getStorage', opts, () => {
			const value = webStore.get('getStorage', opts?.key)
			if (!value.found) throw storageError('getStorage', 'data not found')
			return { result: { data: value.data } }
		})
	}
	return invokeAPI('getStorage', opts)
}

/**
 * https://developers.weixin.qq.com/miniprogram/dev/api/storage/wx.removeStorage.html
 */
export function removeStorage(opts) {
	if (useWebStore()) {
		return runAsyncStorageCall(webStore, 'removeStorage', opts, () => ({ write: webStore.remove('removeStorage', opts?.key) }))
	}
	return invokeAPI('removeStorage', opts)
}

export function clearStorage(opts) {
	if (useWebStore()) {
		return runAsyncStorageCall(webStore, 'clearStorage', opts, () => ({ write: webStore.clear('clearStorage') }))
	}
	return invokeAPI('clearStorage', opts)
}

/**
 * https://developers.weixin.qq.com/miniprogram/dev/api/storage/wx.getStorageInfoSync.html
 */
export function getStorageInfoSync() {
	if (useWebStore()) return webStore.info('getStorageInfoSync')
	return invokeAPI('getStorageInfoSync')
}

/**
 * https://developers.weixin.qq.com/miniprogram/dev/api/storage/wx.getStorageInfo.html
 */
export function getStorageInfo(opts) {
	if (useWebStore()) {
		return runAsyncStorageCall(webStore, 'getStorageInfo', opts, () => ({ result: webStore.info('getStorageInfo') }))
	}
	return invokeAPI('getStorageInfo', opts)
}
