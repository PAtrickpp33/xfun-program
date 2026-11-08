import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createContainer } from '../src/index.js'
import { installFetchMock } from './fixtures/mock-fetch.js'

describe('system info viewport coordinates', () => {
	let mount: HTMLElement

	beforeEach(() => {
		installFetchMock()
		mount = document.createElement('div')
		document.body.appendChild(mount)
	})

	it('uses the logical viewport size instead of its transformed visual bounds', async () => {
		const container = createContainer({ mount })
		const miniApp = await container.openApp({ appId: 'wx-logical-viewport', path: 'pages/index/index' })
		const viewport = mount.querySelector<HTMLElement>('.dimina-native-webview__root')!

		Object.defineProperties(viewport, {
			clientWidth: { configurable: true, value: 409 },
			clientHeight: { configurable: true, value: 865 },
		})
		viewport.getBoundingClientRect = () => ({
			x: 0,
			y: 0,
			top: 0,
			left: 0,
			right: 319,
			bottom: 674,
			width: 319,
			height: 674,
			toJSON: () => ({}),
		})

		const systemInfo = miniApp.getSystemInfoSync()

		expect(systemInfo.windowWidth).toBe(409)
		expect(systemInfo.windowHeight).toBe(865)
		expect(systemInfo.screenWidth).toBe(409)
		expect(systemInfo.screenHeight).toBe(865)
		expect(systemInfo.safeArea).toMatchObject({ right: 409, bottom: 865, width: 409 })

		// The Worker can only request this information asynchronously. Its result must
		// contain the same logical viewport, not omit dimensions or use CSS transforms.
		const postMessage = vi.spyOn(miniApp.jscore!, 'postMessage')
		miniApp.getSystemInfoAsync({ success: 'viewport-success', complete: 'viewport-complete' })
		for (const id of ['viewport-success', 'viewport-complete']) {
			expect(postMessage).toHaveBeenCalledWith({
				type: 'triggerCallback',
				body: {
					id,
					args: expect.objectContaining({
						windowWidth: 409,
						windowHeight: 865,
						screenWidth: 409,
						screenHeight: 865,
						pixelRatio: window.devicePixelRatio,
						errMsg: 'getSystemInfoAsync:ok',
					}),
				},
			})
		}
	}, 10000)
})
