; zatchi - Inno Setup installer
#define MyAppName "zatchi"
#define MyAppVersion "1.0.0"
#define MyAppExe "zatchi.exe"

[Setup]
AppId={{B1E7A4D2-3C5F-4A8E-9D21-7F6C0E9A5B38}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher=zatchi
DefaultDirName={autopf}\zatchi
DefaultGroupName=zatchi
DisableProgramGroupPage=yes
DisableDirPage=no
UsePreviousAppDir=yes
OutputDir=..\dist
OutputBaseFilename=zatchi-setup
SetupIconFile=..\res\zatchi.ico
UninstallDisplayIcon={app}\{#MyAppExe}
UninstallDisplayName={#MyAppName}
Compression=lzma2
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
WizardStyle=modern
AppMutex=zatchi_single_instance_mutex

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; Flags: unchecked
Name: "autostart"; Description: "Start zatchi with Windows (runs hidden in the tray)"; Flags: unchecked

[Files]
Source: "..\dist\{#MyAppExe}"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\zatchi"; Filename: "{app}\{#MyAppExe}"
Name: "{autodesktop}\zatchi"; Filename: "{app}\{#MyAppExe}"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "zatchi"; ValueData: """{app}\{#MyAppExe}"" --background"; Tasks: autostart; Flags: uninsdeletevalue

[Run]
Filename: "{app}\{#MyAppExe}"; Description: "Launch zatchi"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM {#MyAppExe}"; Flags: runhidden; RunOnceId: "KillZatchi"

[Code]
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then
  begin
    RegDeleteValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'zatchi');
    RegDeleteKeyIncludingSubkeys(HKCU, 'Software\zatchi');
    DelTree(ExpandConstant('{localappdata}\zatchi'), True, True, True);
  end;
end;
