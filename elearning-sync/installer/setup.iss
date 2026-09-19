; 复小学 - 复旦大学 eLearning 课程同步 - Inno Setup 安装脚本
; 版本: 1.0.8

#define MyAppName "复小学"
#define MyAppVersion "1.0.8"
#define MyAppPublisher "FuXiaoXue"
#define MyAppExeName "复小学.exe"
#define MyAppId "{{8E6B7D1C-3F6E-4A8C-9B5E-2A3F7C9D1E22}"

[Setup]
AppId={#MyAppId}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\复小学
DefaultGroupName={#MyAppName}
AllowNoIcons=yes
LicenseFile=..\LICENSE
InfoBeforeFile=..\README.md
OutputDir=release
OutputBaseFilename=复小学-Setup-v1.0.8
SetupIconFile=..\build_assets\app.ico
Compression=lzma2/ultra
SolidCompression=yes
WizardStyle=modern
ArchitecturesInstallIn64BitMode=x64
ArchitecturesAllowed=x64
DisableProgramGroupPage=yes
UninstallDisplayIcon={app}\{#MyAppExeName}
AppPublisherURL=https://elearning.fudan.edu.cn
AppSupportURL=https://elearning.fudan.edu.cn

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加图标:"; Flags: unchecked
Name: "autostart"; Description: "开机自动启动"; GroupDescription: "自动启动:"; Flags: unchecked

[Files]
Source: "..\dist\复小学\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\卸载 {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Registry]
; 开机自启的注册表值名与程序内 autostart.py 保持一致（都是“复小学”）
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "复小学"; ValueData: """{app}\{#MyAppExeName}"" --minimized"; Flags: uninsdeletevalue; Tasks: autostart

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "立即运行 {#MyAppName}"; Flags: nowait postinstall skipifsilent
