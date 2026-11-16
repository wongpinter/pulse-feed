"""Product identity shared by the desktop release scripts.

jpackage derives the application image directory and launcher name from
`packageName` in `desktop_app/build.gradle.kts`, so every script that walks a
packaged image has to agree with it. Keep that agreement in one place instead
of repeating literals across the installer, verification and test scripts.
"""

APP_NAME = "Pulse Feed"
FILE_STEM = "PulseFeed"
ARM64_DISPLAY_NAME = f"{APP_NAME} (ARM64)"
ARM64_SHORTCUT_GROUP = f"{APP_NAME} ARM64"
# Stable setup identity for the ARM64 installer; changing it breaks upgrades.
ARM64_APP_ID = "{2705C676-F467-5F1B-95D7-B6EA24D5B1EC}"

METADATA_PATH = "desktop_app/build/generated/desktopMetadata/harmonic-desktop.properties"
ARM64_IMAGE_PATH = f"desktop_app/build/compose/binaries/main-release/app/{APP_NAME}"
ARM64_INSTALLER_PATH = "desktop_app/build/compose/binaries/main-release/exe"