# dmgbuild settings for the macOS disk image: Tiresias.app on the left, an Applications shortcut on the
# right, and a trail of bit blocks between them. Used by `gradle dmg`, which runs:
#
#   dmgbuild -s packaging/dmg-settings.py -D app=path/to/Tiresias.app -D background=build/dmg-art/dmg-background.png \
#       Tiresias build/dist/Tiresias-<version>-macos-aarch64.dmg
#
# dmgbuild finds dmg-background@2x.png next to the background and merges both into one HiDPI .tiff.
# The art is drawn by packaging/DmgBackground.java into build/dmg-art/ on every `gradle dmg`; if you move
# the icons or resize the window here, change the same numbers there.

import os

app = defines["app"]  # noqa: F821 (dmgbuild provides `defines` from the -D options)
app_name = os.path.basename(app)

format = "ULFO"  # LZFSE-compressed, read-only (macOS 10.11 or newer)
filesystem = "HFS+"

files = [app]
symlinks = {"Applications": "/Applications"}
# (no hide_extensions: it needs SetFile from the Xcode tools, and Finder hides ".app" by default anyway)

background = defines["background"]  # noqa: F821

# Window: 660x400 points, opened near the top left of the screen, with no toolbar, sidebar or status bar
window_rect = ((200, 120), (660, 400))
default_view = "icon-view"
show_status_bar = False
show_tab_view = False
show_toolbar = False
show_pathbar = False
show_sidebar = False

# Icon centres, in points from the window's top left. Keep in step with DmgBackground.java.
icon_size = 128
text_size = 13
icon_locations = {
    app_name: (165, 190),
    "Applications": (495, 190),
}
arrange_by = None
show_icon_preview = False
show_item_info = False
label_pos = "bottom"
