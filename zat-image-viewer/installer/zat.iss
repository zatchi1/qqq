; zat Image Viewer - Inno Setup script
#define MyAppName "zat Image Viewer"
#define MyAppVersion "1.0.0"
#define MyAppExe "zat.exe"

[Setup]
AppId={{6B3C1E52-7A0D-4F3B-9C1A-5A7E2D9B4F10}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher=zat
DefaultDirName={autopf}\zat
DefaultGroupName=zat Image Viewer
UsePreviousAppDir=yes
DisableProgramGroupPage=yes
OutputDir=..\dist
OutputBaseFilename=zat-setup
SetupIconFile=..\res\zat.ico
UninstallDisplayIcon={app}\{#MyAppExe}
UninstallDisplayName={#MyAppName}
Compression=lzma2
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
PrivilegesRequiredOverridesAllowed=dialog
ChangesAssociations=yes
WizardStyle=modern
DisableWelcomePage=no

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; Flags: unchecked
Name: "defaultviewer"; Description: "Make zat the &default image viewer for supported formats"; GroupDescription: "File associations:"; Flags: unchecked

[Files]
Source: "..\dist\{#MyAppExe}"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\zat Image Viewer"; Filename: "{app}\{#MyAppExe}"
Name: "{autodesktop}\zat Image Viewer"; Filename: "{app}\{#MyAppExe}"; Tasks: desktopicon

[Registry]
; ProgID
Root: HKA; Subkey: "Software\Classes\zat.Image"; ValueType: string; ValueName: ""; ValueData: "Image (zat)"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\zat.Image\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: "{app}\{#MyAppExe},0"
Root: HKA; Subkey: "Software\Classes\zat.Image\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\{#MyAppExe}"" ""%1"""
; Open With entry
Root: HKA; Subkey: "Software\Classes\Applications\{#MyAppExe}"; ValueType: string; ValueName: "FriendlyAppName"; ValueData: "zat Image Viewer"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\Applications\{#MyAppExe}\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: "{app}\{#MyAppExe},0"
Root: HKA; Subkey: "Software\Classes\Applications\{#MyAppExe}\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\{#MyAppExe}"" ""%1"""
; Default Apps capabilities
Root: HKA; Subkey: "Software\RegisteredApplications"; ValueType: string; ValueName: "zat"; ValueData: "Software\zat\Capabilities"; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\zat\Capabilities"; ValueType: string; ValueName: "ApplicationName"; ValueData: "zat Image Viewer"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\zat\Capabilities"; ValueType: string; ValueName: "ApplicationDescription"; ValueData: "Fast, lightweight image viewer"

Root: HKA; Subkey: "Software\Classes\.jpg\OpenWithProgids"; ValueType: string; ValueName: "zat.Image"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\Applications\zat.exe\SupportedTypes"; ValueType: string; ValueName: ".jpg"; ValueData: ""
Root: HKA; Subkey: "Software\zat\Capabilities\FileAssociations"; ValueType: string; ValueName: ".jpg"; ValueData: "zat.Image"
Root: HKA; Subkey: "Software\Classes\.jpg"; ValueType: string; ValueName: ""; ValueData: "zat.Image"; Flags: uninsdeletevalue; Tasks: defaultviewer
Root: HKA; Subkey: "Software\Classes\.jpeg\OpenWithProgids"; ValueType: string; ValueName: "zat.Image"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\Applications\zat.exe\SupportedTypes"; ValueType: string; ValueName: ".jpeg"; ValueData: ""
Root: HKA; Subkey: "Software\zat\Capabilities\FileAssociations"; ValueType: string; ValueName: ".jpeg"; ValueData: "zat.Image"
Root: HKA; Subkey: "Software\Classes\.jpeg"; ValueType: string; ValueName: ""; ValueData: "zat.Image"; Flags: uninsdeletevalue; Tasks: defaultviewer
Root: HKA; Subkey: "Software\Classes\.jpe\OpenWithProgids"; ValueType: string; ValueName: "zat.Image"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\Applications\zat.exe\SupportedTypes"; ValueType: string; ValueName: ".jpe"; ValueData: ""
Root: HKA; Subkey: "Software\zat\Capabilities\FileAssociations"; ValueType: string; ValueName: ".jpe"; ValueData: "zat.Image"
Root: HKA; Subkey: "Software\Classes\.jpe"; ValueType: string; ValueName: ""; ValueData: "zat.Image"; Flags: uninsdeletevalue; Tasks: defaultviewer
Root: HKA; Subkey: "Software\Classes\.jfif\OpenWithProgids"; ValueType: string; ValueName: "zat.Image"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\Applications\zat.exe\SupportedTypes"; ValueType: string; ValueName: ".jfif"; ValueData: ""
Root: HKA; Subkey: "Software\zat\Capabilities\FileAssociations"; ValueType: string; ValueName: ".jfif"; ValueData: "zat.Image"
Root: HKA; Subkey: "Software\Classes\.jfif"; ValueType: string; ValueName: ""; ValueData: "zat.Image"; Flags: uninsdeletevalue; Tasks: defaultviewer
Root: HKA; Subkey: "Software\Classes\.png\OpenWithProgids"; ValueType: string; ValueName: "zat.Image"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\Applications\zat.exe\SupportedTypes"; ValueType: string; ValueName: ".png"; ValueData: ""
Root: HKA; Subkey: "Software\zat\Capabilities\FileAssociations"; ValueType: string; ValueName: ".png"; ValueData: "zat.Image"
Root: HKA; Subkey: "Software\Classes\.png"; ValueType: string; ValueName: ""; ValueData: "zat.Image"; Flags: uninsdeletevalue; Tasks: defaultviewer
Root: HKA; Subkey: "Software\Classes\.bmp\OpenWithProgids"; ValueType: string; ValueName: "zat.Image"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\Applications\zat.exe\SupportedTypes"; ValueType: string; ValueName: ".bmp"; ValueData: ""
Root: HKA; Subkey: "Software\zat\Capabilities\FileAssociations"; ValueType: string; ValueName: ".bmp"; ValueData: "zat.Image"
Root: HKA; Subkey: "Software\Classes\.bmp"; ValueType: string; ValueName: ""; ValueData: "zat.Image"; Flags: uninsdeletevalue; Tasks: defaultviewer
Root: HKA; Subkey: "Software\Classes\.dib\OpenWithProgids"; ValueType: string; ValueName: "zat.Image"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\Applications\zat.exe\SupportedTypes"; ValueType: string; ValueName: ".dib"; ValueData: ""
Root: HKA; Subkey: "Software\zat\Capabilities\FileAssociations"; ValueType: string; ValueName: ".dib"; ValueData: "zat.Image"
Root: HKA; Subkey: "Software\Classes\.dib"; ValueType: string; ValueName: ""; ValueData: "zat.Image"; Flags: uninsdeletevalue; Tasks: defaultviewer
Root: HKA; Subkey: "Software\Classes\.gif\OpenWithProgids"; ValueType: string; ValueName: "zat.Image"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\Applications\zat.exe\SupportedTypes"; ValueType: string; ValueName: ".gif"; ValueData: ""
Root: HKA; Subkey: "Software\zat\Capabilities\FileAssociations"; ValueType: string; ValueName: ".gif"; ValueData: "zat.Image"
Root: HKA; Subkey: "Software\Classes\.gif"; ValueType: string; ValueName: ""; ValueData: "zat.Image"; Flags: uninsdeletevalue; Tasks: defaultviewer
Root: HKA; Subkey: "Software\Classes\.webp\OpenWithProgids"; ValueType: string; ValueName: "zat.Image"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\Applications\zat.exe\SupportedTypes"; ValueType: string; ValueName: ".webp"; ValueData: ""
Root: HKA; Subkey: "Software\zat\Capabilities\FileAssociations"; ValueType: string; ValueName: ".webp"; ValueData: "zat.Image"
Root: HKA; Subkey: "Software\Classes\.webp"; ValueType: string; ValueName: ""; ValueData: "zat.Image"; Flags: uninsdeletevalue; Tasks: defaultviewer
Root: HKA; Subkey: "Software\Classes\.tif\OpenWithProgids"; ValueType: string; ValueName: "zat.Image"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\Applications\zat.exe\SupportedTypes"; ValueType: string; ValueName: ".tif"; ValueData: ""
Root: HKA; Subkey: "Software\zat\Capabilities\FileAssociations"; ValueType: string; ValueName: ".tif"; ValueData: "zat.Image"
Root: HKA; Subkey: "Software\Classes\.tif"; ValueType: string; ValueName: ""; ValueData: "zat.Image"; Flags: uninsdeletevalue; Tasks: defaultviewer
Root: HKA; Subkey: "Software\Classes\.tiff\OpenWithProgids"; ValueType: string; ValueName: "zat.Image"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\Applications\zat.exe\SupportedTypes"; ValueType: string; ValueName: ".tiff"; ValueData: ""
Root: HKA; Subkey: "Software\zat\Capabilities\FileAssociations"; ValueType: string; ValueName: ".tiff"; ValueData: "zat.Image"
Root: HKA; Subkey: "Software\Classes\.tiff"; ValueType: string; ValueName: ""; ValueData: "zat.Image"; Flags: uninsdeletevalue; Tasks: defaultviewer
Root: HKA; Subkey: "Software\Classes\.ico\OpenWithProgids"; ValueType: string; ValueName: "zat.Image"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\Applications\zat.exe\SupportedTypes"; ValueType: string; ValueName: ".ico"; ValueData: ""
Root: HKA; Subkey: "Software\zat\Capabilities\FileAssociations"; ValueType: string; ValueName: ".ico"; ValueData: "zat.Image"
Root: HKA; Subkey: "Software\Classes\.ico"; ValueType: string; ValueName: ""; ValueData: "zat.Image"; Flags: uninsdeletevalue; Tasks: defaultviewer
Root: HKA; Subkey: "Software\Classes\.svg\OpenWithProgids"; ValueType: string; ValueName: "zat.Image"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\Applications\zat.exe\SupportedTypes"; ValueType: string; ValueName: ".svg"; ValueData: ""
Root: HKA; Subkey: "Software\zat\Capabilities\FileAssociations"; ValueType: string; ValueName: ".svg"; ValueData: "zat.Image"
Root: HKA; Subkey: "Software\Classes\.svg"; ValueType: string; ValueName: ""; ValueData: "zat.Image"; Flags: uninsdeletevalue; Tasks: defaultviewer
Root: HKA; Subkey: "Software\Classes\.avif\OpenWithProgids"; ValueType: string; ValueName: "zat.Image"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\Applications\zat.exe\SupportedTypes"; ValueType: string; ValueName: ".avif"; ValueData: ""
Root: HKA; Subkey: "Software\zat\Capabilities\FileAssociations"; ValueType: string; ValueName: ".avif"; ValueData: "zat.Image"
Root: HKA; Subkey: "Software\Classes\.avif"; ValueType: string; ValueName: ""; ValueData: "zat.Image"; Flags: uninsdeletevalue; Tasks: defaultviewer
Root: HKA; Subkey: "Software\Classes\.heic\OpenWithProgids"; ValueType: string; ValueName: "zat.Image"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\Applications\zat.exe\SupportedTypes"; ValueType: string; ValueName: ".heic"; ValueData: ""
Root: HKA; Subkey: "Software\zat\Capabilities\FileAssociations"; ValueType: string; ValueName: ".heic"; ValueData: "zat.Image"
Root: HKA; Subkey: "Software\Classes\.heic"; ValueType: string; ValueName: ""; ValueData: "zat.Image"; Flags: uninsdeletevalue; Tasks: defaultviewer
Root: HKA; Subkey: "Software\Classes\.heif\OpenWithProgids"; ValueType: string; ValueName: "zat.Image"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\Applications\zat.exe\SupportedTypes"; ValueType: string; ValueName: ".heif"; ValueData: ""
Root: HKA; Subkey: "Software\zat\Capabilities\FileAssociations"; ValueType: string; ValueName: ".heif"; ValueData: "zat.Image"
Root: HKA; Subkey: "Software\Classes\.heif"; ValueType: string; ValueName: ""; ValueData: "zat.Image"; Flags: uninsdeletevalue; Tasks: defaultviewer
Root: HKA; Subkey: "Software\Classes\.jxr\OpenWithProgids"; ValueType: string; ValueName: "zat.Image"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\Applications\zat.exe\SupportedTypes"; ValueType: string; ValueName: ".jxr"; ValueData: ""
Root: HKA; Subkey: "Software\zat\Capabilities\FileAssociations"; ValueType: string; ValueName: ".jxr"; ValueData: "zat.Image"
Root: HKA; Subkey: "Software\Classes\.jxr"; ValueType: string; ValueName: ""; ValueData: "zat.Image"; Flags: uninsdeletevalue; Tasks: defaultviewer
Root: HKA; Subkey: "Software\Classes\.wdp\OpenWithProgids"; ValueType: string; ValueName: "zat.Image"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\Applications\zat.exe\SupportedTypes"; ValueType: string; ValueName: ".wdp"; ValueData: ""
Root: HKA; Subkey: "Software\zat\Capabilities\FileAssociations"; ValueType: string; ValueName: ".wdp"; ValueData: "zat.Image"
Root: HKA; Subkey: "Software\Classes\.wdp"; ValueType: string; ValueName: ""; ValueData: "zat.Image"; Flags: uninsdeletevalue; Tasks: defaultviewer
Root: HKA; Subkey: "Software\Classes\.jxl\OpenWithProgids"; ValueType: string; ValueName: "zat.Image"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\Applications\zat.exe\SupportedTypes"; ValueType: string; ValueName: ".jxl"; ValueData: ""
Root: HKA; Subkey: "Software\zat\Capabilities\FileAssociations"; ValueType: string; ValueName: ".jxl"; ValueData: "zat.Image"
Root: HKA; Subkey: "Software\Classes\.jxl"; ValueType: string; ValueName: ""; ValueData: "zat.Image"; Flags: uninsdeletevalue; Tasks: defaultviewer

[Run]
Filename: "{app}\{#MyAppExe}"; Description: "Launch zat"; Flags: nowait postinstall skipifsilent unchecked

[UninstallDelete]
Type: filesandordirs; Name: "{app}"

[Code]
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then
    RegDeleteKeyIncludingSubkeys(HKCU, 'Software\zat');
end;
