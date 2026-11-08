# TypeScript、Less 和 Sass 编译

DMCC 可以读取 TypeScript、Less、SCSS 和 Sass 文件，并把它们转换为小程序运行时使用的 JavaScript 与 CSS。

## 文件查找顺序

同一路径存在多个候选文件时，编译器按以下顺序选择：

1. 逻辑文件：`.js` → `.ts`
2. 样式文件：`.wxss` → `.ddss` → `.less` → `.scss` → `.sass`

因此，同名 `.js` 与 `.ts` 同时存在时会使用 `.js`；样式文件也以排在前面的扩展名为准。

## TypeScript

TypeScript 页面和组件沿用小程序原有的文件结构：

```text
pages/index/
├── index.ts
├── index.wxml
├── index.scss
└── index.json
```

```ts
interface PageData {
  message: string
  count: number
}

Page<PageData>({
  data: {
    message: 'Hello TypeScript',
    count: 0,
  },

  increment() {
    this.setData({ count: this.data.count + 1 })
  },
})
```

编译器使用 esbuild 转换逻辑文件，固定输出 CommonJS，目标语法为 ES2020。相对路径、以 `/` 开头的小程序绝对路径和 `miniprogram_npm` 包路径都由 DMCC 的模块解析逻辑处理。

```ts
import { formatDate } from '../../utils/helper'
import Toast from '@vant/weapp/toast/toast'
import { request } from '/utils/api'
```

DMCC 只移除类型语法并转换模块，不执行 TypeScript 类型检查，也不读取项目的 `tsconfig.json`。需要类型检查时，请在业务工程中单独运行 `tsc --noEmit` 或现有检查命令。

如果 esbuild 转换失败，编译器会记录错误并保留已经完成路径改写的源码。对于仍含 TypeScript 语法的文件，该产物通常无法在运行时执行，因此发布前应把编译日志中的转换错误视为失败处理。

## Less

`.less` 文件由 Less 编译，支持变量、mixin、嵌套和 Less 内置函数：

```less
@primary-color: #1890ff;

.button {
  background: @primary-color;

  &:hover {
    opacity: 0.8;
  }
}
```

## SCSS 和 Sass

`.scss` 使用 SCSS 语法，`.sass` 使用缩进语法。两者都由 Dart Sass 编译，并支持 Sass 模块、变量、mixin 和嵌套。

```scss
@use 'sass:color';

$primary-color: #ff6b35;

.button {
  background: $primary-color;

  &:hover {
    background: color.adjust($primary-color, $lightness: -10%);
  }
}
```

预处理器的加载路径包含当前文件目录和小程序工程根目录。样式中的 `@import` 会进入 DMCC 的依赖图，以便 watch 和增量编译判断受影响文件。

## 样式后处理

Less 或 Sass 输出还会经过 Dimina 的统一样式管线，包括：

- `rpx` 转换；
- 内置组件标签改写；
- 组件作用域与 `:host` 处理；
- `url()` 资源路径处理；
- Autoprefixer；
- 启用 source map 时的映射串联。

预处理、作用域转换或 PostCSS 处理失败时，编译器会抛出带阶段和文件路径的错误，不会静默使用原始样式。

## 缓存与增量编译

单次构建会复用已处理样式的内存缓存。通过仓库的编译入口运行时，持久化编译缓存还会根据文件指纹和依赖图跳过未变化工程，或只重建受影响的 logic、view、style 阶段。

仓库示例批量编译需要忽略持久化缓存时使用：

```sh
cd fe
pnpm compile --force
```

`--force` 属于仓库的 `pnpm compile` 脚本，不是 `dmcc build` 的参数。

## 依赖与验证

Less、Sass 和 esbuild 已列入 `@xfun/compiler` 的运行依赖，业务项目无需重复声明。实际版本以 `packages/compiler/package.json` 和 `pnpm-lock.yaml` 为准。

相关测试：

- [TypeScript 转换](../__tests__/typescript-support.spec.js)
- [样式编译](../__tests__/style-compiler.spec.js)与[错误契约](../__tests__/style-error-contract.spec.js)
- [增量缓存](../__tests__/compile-cache.spec.js)
