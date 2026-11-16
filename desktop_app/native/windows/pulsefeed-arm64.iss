; jpackage 21 builds ARM64 application images, but its MSI authoring assumes x64.
; Inno Setup installs the complete native image, including the bundled Java runtime.
#ifndef AppImage
  #error AppImage must name the packaged application image directory
#endif
#ifndef AppVersion
  #error AppVersion must match the packaged desktop metadata
#endif
#ifndef InstallerOutput
  #error InstallerOutput must name the installer output directory
#endif
#ifndef LauncherName
  #define LauncherName "Pulse Feed"
#endif

[Setup]
AppId={{2705C676-F467-5F1B-95D7-B6EA24D5B1EC}
AppName=Pulse Feed (ARM64)
AppVersion={#AppVersion}
AppPublisher=Pulse Feed (Forked from Harmonic by Simon Halvdansson)
UninstallDisplayName=Pulse Feed (ARM64)
DefaultDirName={autopf}\Pulse Feed ARM64
DefaultGroupName=Pulse Feed ARM64
PrivilegesRequired=lowest
ArchitecturesAllowed=arm64
ArchitecturesInstallIn64BitMode=arm64
MinVersion=10.0.22000
OutputDir={#InstallerOutput}
OutputBaseFilename=PulseFeed-{#AppVersion}-windows-arm64
SetupIconFile=..\..\icons\pulsefeed.ico
UninstallDisplayIcon={app}\{#LauncherName}.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
RestartApplications=no

[InstallDelete]
; Only replace application payload. Settings, credentials and downloaded models
; live outside the installation directory and must survive upgrades/uninstallation.
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "{#AppImage}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Pulse Feed (ARM64)"; Filename: "{app}\{#LauncherName}.exe"; WorkingDir: "{app}"

[Run]
Filename: "{app}\{#LauncherName}.exe"; Description: "Launch Pulse Feed"; Flags: nowait postinstall skipifsilent
