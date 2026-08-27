from pathlib import Path

root = Path('project/app/src/main/java/com/teslalyrics/app')
main = root / 'MainActivity.java'
s = main.read_text()
s = s.replace(
    'private final AppState state=AppState.get();private SettingsStore settings;private TeslaAuthManager auth;private TelemetrySetupManager telemetrySetup;',
    'private final AppState state=AppState.get();private SettingsStore settings;private TeslaAuthManager auth;private TelemetrySetupManager telemetrySetup;private PartnerRegistrationManager partnerRegistration;'
)
s = s.replace(
    'auth=new TeslaAuthManager(this,settings);telemetrySetup=new TelemetrySetupManager(this,settings,auth);buildUi();',
    'auth=new TeslaAuthManager(this,settings);telemetrySetup=new TelemetrySetupManager(this,settings,auth);partnerRegistration=new PartnerRegistrationManager(settings);buildUi();'
)
s = s.replace(
    'rowButton("保存设置",this::saveSettings);rowButton("配对 Tesla Virtual Key",()->telemetrySetup.pairVirtualKey(this::showResult));',
    'rowButton("保存设置",this::saveSettings);rowButton("注册 Tesla Partner",()->partnerRegistration.register(this::showResult));rowButton("验证 Partner / Public Key",()->partnerRegistration.verify(this::showResult));rowButton("配对 Tesla Virtual Key",()->telemetrySetup.pairVirtualKey(this::showResult));'
)
if '注册 Tesla Partner' not in s:
    raise SystemExit('MainActivity patch failed')
main.write_text(s)
(root / 'PartnerRegistrationManager.java').write_text(Path('patches/PartnerRegistrationManager.java').read_text())
print('Partner registration patch applied')
