import { common } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { FlutterAbility, FlutterEngine } from '@ohos/flutter_ohos';
import { DMPApp } from '@xfun/sdk';
import { DMPEntryContext } from '@xfun/sdk/src/main/ets/DApp/config/DMPEntryContext';
import { GeneratedPluginRegistrant } from '../plugins/GeneratedPluginRegistrant';
import DiminaFlutterPlugin from '../plugin/DiminaFlutterPlugin';

export default class EntryAbility extends FlutterAbility {
  configureFlutterEngine(flutterEngine: FlutterEngine): void {
    super.configureFlutterEngine(flutterEngine);
    GeneratedPluginRegistrant.registerWith(flutterEngine);
    this.addPlugin(new DiminaFlutterPlugin());
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    const entryContext: DMPEntryContext = {
      getContext: (): common.UIAbilityContext => this.context,
      getWindowStage: (): window.WindowStage => windowStage
    };
    DMPApp.init(entryContext);
    super.onWindowStageCreate(windowStage);
  }
}
