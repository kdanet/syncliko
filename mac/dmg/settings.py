# dmgbuild settings for the Syncliko disk image (used by mac/package.sh).
# The window shows Syncliko and an Applications shortcut side by side over brand/dmg-background.svg.
import os.path

app = defines.get("app", "build/Syncliko.app")  # noqa: F821 (defines is injected by dmgbuild)
here = os.path.dirname(os.path.abspath(defines.get("settings_dir", ".")))  # noqa: F821

format = "ULFO"  # LZFSE, macOS 10.11+
filesystem = "APFS"
files = [app]
symlinks = {"Applications": "/Applications"}
icon = os.path.join(app, "Contents/Resources/AppIcon.icns")
background = defines.get("background")  # noqa: F821
show_status_bar = False
show_tab_view = False
show_toolbar = False
show_pathbar = False
show_sidebar = False
window_rect = ((200, 120), (640, 440))
hide_extension = ["Syncliko.app"]
default_view = "icon-view"
icon_size = 112
text_size = 13
icon_locations = {"Syncliko.app": (170, 190), "Applications": (470, 190)}
