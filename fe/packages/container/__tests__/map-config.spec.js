// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { installDemoMapConfig } from '../src/mapConfig'

vi.mock('@xfun/container-sdk/pageFrame', () => ({}))
vi.mock('@xfun/container-sdk/pageFrame.css', () => ({}))

const configured = {
	VITE_AMAP_WEB_KEY: 'test-web-key',
	VITE_AMAP_SECURITY_JS_CODE: 'test-security-code',
	VITE_AMAP_PRIVACY_CONSENT: 'true',
}

afterEach(() => {
	vi.unstubAllEnvs()
	delete window.__DIMINA_MAP_CONFIG__
	delete window.DiminaRenderBridge
	vi.resetModules()
})

describe('Web demo map configuration', () => {
	it('installs the environment configuration on the actual pageFrame entry window', async () => {
		for (const [key, value] of Object.entries(configured)) vi.stubEnv(key, value)
		await import('../src/pageFrameEntry')
		expect(window.__DIMINA_MAP_CONFIG__).toBeUndefined()
		window.DiminaRenderBridge = { mapRenderer: 'web' }
		const config = window.__DIMINA_MAP_CONFIG__
		expect(config?.provider).toBe('amap')
		expect(config.providerOptions.amap.key).toBe('test-web-key')
		expect(config.authorize()).toBe(true)
	})

	it('reports the missing Web key with an actionable configuration path', () => {
		const frame = { DiminaRenderBridge: { mapRenderer: 'web' } }
		installDemoMapConfig(frame, {})
		expect(() => frame.__DIMINA_MAP_CONFIG__.authorize()).toThrow('fe/packages/container/.env.local')
	})

	it.each([
		{},
		{ VITE_AMAP_SECURITY_JS_CODE: '', VITE_AMAP_SERVICE_HOST: '' },
		{ VITE_AMAP_SECURITY_JS_CODE: '  ', VITE_AMAP_SERVICE_HOST: '  ' },
	])('accepts a Web key without optional security settings: %j', (optional) => {
		const frame = { DiminaRenderBridge: { mapRenderer: 'web' } }
		installDemoMapConfig(frame, { VITE_AMAP_WEB_KEY: 'test-web-key', VITE_AMAP_PRIVACY_CONSENT: 'true', ...optional })
		expect(frame.__DIMINA_MAP_CONFIG__.authorize()).toBe(true)
		expect(frame.__DIMINA_MAP_CONFIG__.providerOptions.amap).toEqual({ key: 'test-web-key' })
	})

	it('requires explicit demo consent before SDK loading', () => {
		const denied = { DiminaRenderBridge: { mapRenderer: 'web' } }
		installDemoMapConfig(denied, { ...configured, VITE_AMAP_PRIVACY_CONSENT: 'false' })
		expect(() => denied.__DIMINA_MAP_CONFIG__.authorize()).toThrow('地图服务尚未授权')
	})

	it('prefers a server proxy and isolates configuration between frames', () => {
		const first = { DiminaRenderBridge: { mapRenderer: 'web' } }
		const second = { DiminaRenderBridge: { mapRenderer: 'web' } }
		installDemoMapConfig(first, { ...configured, VITE_AMAP_SERVICE_HOST: 'https://maps.example/_AMapService' })
		installDemoMapConfig(second, { ...configured, VITE_AMAP_WEB_KEY: 'another-web-key' })
		expect(first.__DIMINA_MAP_CONFIG__.providerOptions.amap).toEqual({ key: 'test-web-key', serviceHost: 'https://maps.example/_AMapService' })
		expect(second.__DIMINA_MAP_CONFIG__.providerOptions.amap.key).toBe('another-web-key')
	})

	it('preserves a custom host provider', () => {
		const custom = { provider: 'custom' }
		const frame = { __DIMINA_MAP_CONFIG__: custom }
		installDemoMapConfig(frame, configured)
		expect(frame.__DIMINA_MAP_CONFIG__).toBe(custom)
	})

	it('leaves native containers on the native SDK path and permits later host configuration', () => {
		const nativeFrame = { DiminaRenderBridge: {} }
		installDemoMapConfig(nativeFrame, configured)
		expect(nativeFrame.__DIMINA_MAP_CONFIG__).toBeUndefined()
		const custom = { provider: 'custom' }
		nativeFrame.__DIMINA_MAP_CONFIG__ = custom
		expect(nativeFrame.__DIMINA_MAP_CONFIG__).toBe(custom)
	})
})
