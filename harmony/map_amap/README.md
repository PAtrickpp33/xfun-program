# Dimina 高德地图适配器

为 Dimina 提供可选的高德原生地图能力。请安装与 Dimina SDK 版本一致的适配器：

```sh
ohpm install @xfun/map-amap
```

```ts
import { DMPMapProviders } from '@xfun/sdk';
import { DMPAMapProvider } from '@xfun/map-amap';

DMPMapProviders.register('amap', new DMPAMapProvider(context, apiKey, () => privacyConsentGranted));
```

宿主需提供高德平台 Key、实际的隐私授权状态和定位权限。完整配置见[地图接入文档](https://github.com/didi/dimina/blob/main/docs/Map-Integration.md)。核心 SDK 不依赖此可选适配器。
